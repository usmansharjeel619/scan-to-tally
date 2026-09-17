// Command connector is the on-prem bridge between the relay and Tally Prime.
//
// It runs as a Windows service on the machine that holds the Tally data. It
// dials OUT to the relay and talks to Tally only over localhost, so nothing on
// the warehouse machine is reachable from a network -- which matters because
// Tally's XML gateway has no authentication whatsoever.
//
//	connector -config connector.json
//	connector -print-config > connector.json     # a starting template
package main

import (
	"bytes"
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"
	"time"

	"github.com/acme/scan-to-tally/connector/internal/protocol"
	"github.com/acme/scan-to-tally/connector/internal/relayclient"
	"github.com/acme/scan-to-tally/connector/internal/runner"
	"github.com/acme/scan-to-tally/connector/internal/store"
	"github.com/acme/scan-to-tally/connector/internal/tally"
)

var version = "0.1.0"

type Config struct {
	Tally struct {
		BaseURL       string `json:"baseUrl"`
		Company       string `json:"company"`
		TimeoutSec    int    `json:"timeoutSec"`
		ProbeSeconds  int    `json:"probeSeconds"`
		// TDL overrides the built-in collection queries. Tally's TDL surface
		// varies by version and configuration, so a site must be correctable
		// from this file rather than by rebuilding the service.
		TDL tally.TDLOverrides `json:"tdl"`
	} `json:"tally"`

	Relay struct {
		URL         string `json:"url"`
		Secret      string `json:"secret"`
		ConnectorID string `json:"connectorId"`
		SyncSeconds int    `json:"syncSeconds"`
	} `json:"relay"`

	// Diagnostics enables the Phase 0 read-only query channel. OFF by default.
	// It lets a remote operator run Export requests against this Tally so the
	// XML templates can be reconciled against a real installation. Imports are
	// refused; vouchers go through the normal job path. Turn it off when
	// Phase 0 is finished.
	Diagnostics struct {
		Enabled  bool `json:"enabled"`
		MaxBytes int  `json:"maxBytes"`
	} `json:"diagnostics"`

	// Masters controls whether this connector may write to Tally's item
	// master. OFF by default: a warehouse scanner creating accounting records
	// is a decision a site makes, not something that arrives switched on.
	Masters struct {
		AllowCreate   bool   `json:"allowCreate"`
		DefaultParent string `json:"defaultParent"`
	} `json:"masters"`

	DBPath string `json:"dbPath"`
	// LogFile is where a service writes its log, since it has no console.
	LogFile string `json:"logFile"`
	// StatusAddr exposes a local status page. Loopback only: it reveals stock
	// figures and should never be bound to a routable address.
	StatusAddr string `json:"statusAddr"`
	LogLevel   string `json:"logLevel"`
}

func defaultConfig() Config {
	var c Config
	c.Tally.BaseURL = "http://127.0.0.1:9000"
	c.Tally.Company = "YOUR COMPANY NAME EXACTLY AS TALLY SPELLS IT"
	c.Tally.TimeoutSec = 30
	c.Tally.ProbeSeconds = 15
	c.Relay.URL = "wss://relay.example.com/connector/ws"
	c.Relay.Secret = "CHANGE-ME"
	c.Relay.SyncSeconds = 120
	c.DBPath = "connector.db"
	c.LogFile = "connector.log"
	c.StatusAddr = "127.0.0.1:9787"
	c.LogLevel = "info"
	return c
}

func main() {
	cfgPath := flag.String("config", "connector.json", "path to the config file")
	printCfg := flag.Bool("print-config", false, "write a template config to stdout and exit")
	showVer := flag.Bool("version", false, "print the version and exit")
	flag.Parse()

	if *showVer {
		fmt.Println("scan-to-tally connector", version)
		return
	}
	if *printCfg {
		enc := json.NewEncoder(os.Stdout)
		enc.SetIndent("", "  ")
		_ = enc.Encode(defaultConfig())
		return
	}

	cfg, err := loadConfig(*cfgPath)
	if err != nil {
		fmt.Fprintln(os.Stderr, "config:", err)
		os.Exit(1)
	}

	log := newLogger(cfg.LogLevel, cfg.LogFile)
	slog.SetDefault(log)
	log.Info("starting", "version", version, "company", cfg.Tally.Company, "db", cfg.DBPath)

	st, err := store.Open(cfg.DBPath)
	if err != nil {
		log.Error("could not open the local database", "path", cfg.DBPath, "err", err)
		os.Exit(1)
	}
	defer st.Close()

	tc := tally.NewClient(tally.Config{
		BaseURL:       cfg.Tally.BaseURL,
		Company:       cfg.Tally.Company,
		Timeout:       time.Duration(cfg.Tally.TimeoutSec) * time.Second,
		ProbeInterval: time.Duration(cfg.Tally.ProbeSeconds) * time.Second,
		TDL:           cfg.Tally.TDL,
	}, log)

	rc := relayclient.New(relayclient.Config{
		URL:          cfg.Relay.URL,
		Secret:       cfg.Relay.Secret,
		ConnectorID:  cfg.Relay.ConnectorID,
		Company:      cfg.Tally.Company,
		Version:      version,
		SyncInterval: time.Duration(cfg.Relay.SyncSeconds) * time.Second,
	}, st, healthAdapter{tc}, makeSyncer(tc), log)

	if cfg.Diagnostics.Enabled {
		diag := tally.Diagnostics{Enabled: true, MaxBytes: cfg.Diagnostics.MaxBytes}
		rc.SetQuerier(func(ctx context.Context, label, xmlPayload string) (string, time.Duration, error) {
			return tc.Query(ctx, diag, label, xmlPayload)
		})
		log.Warn("DIAGNOSTICS ENABLED -- the relay can run read-only Tally queries. " +
			"Turn this off in the config file when Phase 0 is finished.")
	}

	if cfg.Masters.AllowCreate {
		policy := tally.MasterPolicy{
			AllowCreate:   true,
			DefaultParent: cfg.Masters.DefaultParent,
		}
		rc.SetItemCreator(func(ctx context.Context, j protocol.CreateStockItemJob) (string, error) {
			return tc.CreateStockItem(ctx, policy, tally.NewStockItem{
				Name: j.Name, BaseUnits: j.BaseUnits,
				Batchwise: j.Batchwise, TrackMfgDate: j.TrackMfgDate,
			})
		})
		log.Warn("MASTER CREATION ENABLED -- an approved proposal can add a stock item "+
			"to Tally. A stock item cannot be deleted once it has transactions.",
			"company", cfg.Tally.Company, "defaultGroup", cfg.Masters.DefaultParent)
	}

	run := runner.New(st, tc, rc, log, runner.Options{})

	// The body of the program, independent of how it was started.
	work := func(ctx context.Context) {
		go tc.Run(ctx)  // health heartbeat
		go rc.Run(ctx)  // relay socket, reconnecting
		go run.Run(ctx) // the single Tally worker

		if cfg.StatusAddr != "" {
			go serveStatus(ctx, cfg.StatusAddr, st, tc, log)
		}

		<-ctx.Done()
		log.Info("shutting down")
		// Jobs are durable in SQLite, so stopping mid-queue is safe: anything
		// left 'running' is requeued on the next start and recognised by the
		// idempotency check rather than posted twice.
		time.Sleep(500 * time.Millisecond)
	}

	// Under the Windows Service Control Manager this must speak the service
	// protocol; SCM kills a process that does not report Running.
	handled, err := runUnderServiceManager(work)
	if err != nil {
		log.Error("service dispatcher failed", "err", err)
		os.Exit(1)
	}
	if handled {
		return
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	log.Info("running as a console program; press ctrl-c to stop")
	work(ctx)
}

func loadConfig(path string) (Config, error) {
	cfg := defaultConfig()
	data, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			return cfg, fmt.Errorf("%s not found. Run:  connector -print-config > %s", path, filepath.Base(path))
		}
		return cfg, err
	}
	// Strip a UTF-8 BOM. Windows PowerShell's Set-Content -Encoding UTF8 writes
	// one, and so does Notepad, and Go's JSON parser rejects it outright with a
	// message that gives no hint what is wrong.
	data = bytes.TrimPrefix(data, []byte{0xEF, 0xBB, 0xBF})

	if err := json.Unmarshal(bytes.TrimSpace(data), &cfg); err != nil {
		return cfg, fmt.Errorf("%s is not valid JSON: %w", path, err)
	}
	if cfg.Relay.Secret == "" || cfg.Relay.Secret == "CHANGE-ME" {
		return cfg, fmt.Errorf("relay.secret is not set in %s", path)
	}
	if cfg.Tally.Company == "" || cfg.Tally.Company == defaultConfig().Tally.Company {
		return cfg, fmt.Errorf("tally.company is not set in %s", path)
	}
	return cfg, nil
}

// newLogger writes to stdout and, when configured, to a file.
//
// A Windows service has no console at all, so without a log file the only
// evidence of what went wrong is the Event Log -- which is markedly harder to
// read over someone's shoulder on a warehouse PC.
func newLogger(level, file string) *slog.Logger {
	lvl := slog.LevelInfo
	switch level {
	case "debug":
		lvl = slog.LevelDebug
	case "warn":
		lvl = slog.LevelWarn
	case "error":
		lvl = slog.LevelError
	}
	var out io.Writer = os.Stdout
	if file != "" {
		if f, err := os.OpenFile(file, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o600); err == nil {
			out = io.MultiWriter(os.Stdout, f)
		}
	}
	return slog.New(slog.NewTextHandler(out, &slog.HandlerOptions{Level: lvl}))
}

// healthAdapter narrows the Tally client to what the heartbeat needs.
type healthAdapter struct{ c *tally.Client }

func (h healthAdapter) Health() string       { return string(h.c.Health()) }
func (h healthAdapter) LastSeen() time.Time  { return h.c.LastSeen() }
func (h healthAdapter) LastError() string    { return h.c.LastError() }

// makeSyncer reads the master data the relay caches and fans out to devices.
func makeSyncer(tc *tally.Client) relayclient.Syncer {
	return func(ctx context.Context) (*protocol.SyncPush, error) {
		items, err := tc.ListStockItems(ctx)
		if err != nil {
			return nil, err
		}
		balances, err := tc.ListBatchBalances(ctx)
		if err != nil {
			return nil, err
		}
		// A generous window: an order raised months ago can still be open, and
		// an order the device cannot see is one the operator cannot despatch.
		orders, err := tc.ListSalesOrders(ctx,
			time.Now().AddDate(-1, 0, 0), time.Now().AddDate(0, 3, 0))
		if err != nil {
			return nil, err
		}

		out := &protocol.SyncPush{Company: tc.Company(), SyncedAt: time.Now()}
		godowns := map[string]bool{}

		for _, i := range items {
			out.Items = append(out.Items, protocol.SyncItem{
				Name: i.Name, Alias: i.Alias, PartNo: i.PartNo,
				BaseUnits: i.BaseUnits, HasBatches: i.HasBatches,
			})
		}
		for _, b := range balances {
			out.Balances = append(out.Balances, protocol.SyncBalance{
				StockItemName: b.StockItemName, BatchName: b.BatchName,
				GodownName: b.GodownName, ClosingQty: b.ClosingQty, Unit: b.Unit,
			})
			if b.GodownName != "" {
				godowns[b.GodownName] = true
			}
		}
		for g := range godowns {
			out.Godowns = append(out.Godowns, g)
		}
		for _, o := range orders {
			if o.IsFullyDelivered() {
				continue // nothing left to pick
			}
			so := protocol.SyncOrder{
				VoucherNumber: o.VoucherNumber, PartyName: o.PartyName, Date: o.Date,
			}
			for _, l := range o.Lines {
				so.Lines = append(so.Lines, protocol.SyncOrderLine{
					StockItemName: l.StockItemName, OrderedQty: l.OrderedQty,
					DeliveredQty: l.DeliveredQty, Unit: l.Unit,
				})
			}
			out.Orders = append(out.Orders, so)
		}
		return out, nil
	}
}

// serveStatus exposes a plain-text status page for whoever is standing at the
// Tally machine wondering why the warehouse app says OFFLINE.
func serveStatus(ctx context.Context, addr string, st *store.Store, tc *tally.Client, log *slog.Logger) {
	mux := http.NewServeMux()
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		counts, _ := st.Counts(r.Context())
		failed, _ := st.FailedJobs(r.Context(), 20)

		fmt.Fprintf(w, "scan-to-tally connector %s\n\n", version)
		fmt.Fprintf(w, "Tally        : %s\n", tc.Health())
		fmt.Fprintf(w, "Company      : %s\n", tc.Company())
		if ls := tc.LastSeen(); !ls.IsZero() {
			fmt.Fprintf(w, "Last answered: %s (%s ago)\n",
				ls.Format(time.RFC3339), time.Since(ls).Round(time.Second))
		}
		if e := tc.LastError(); e != "" {
			fmt.Fprintf(w, "Last error   : %s\n", e)
		}
		fmt.Fprintf(w, "\nQueue: queued=%d running=%d done=%d failed=%d\n",
			counts[store.JobQueued], counts[store.JobRunning],
			counts[store.JobDone], counts[store.JobFailed])

		if len(failed) > 0 {
			fmt.Fprintf(w, "\nNeeds attention:\n")
			for _, j := range failed {
				fmt.Fprintf(w, "  %s  %s\n    %s\n", j.SessionID, j.ErrorClass, j.LastError)
			}
		}
	})

	srv := &http.Server{Addr: addr, Handler: mux, ReadHeaderTimeout: 5 * time.Second}
	go func() { <-ctx.Done(); _ = srv.Close() }()
	log.Info("status page", "url", "http://"+addr)
	if err := srv.ListenAndServe(); err != nil && ctx.Err() == nil {
		log.Warn("status server stopped", "err", err)
	}
}
