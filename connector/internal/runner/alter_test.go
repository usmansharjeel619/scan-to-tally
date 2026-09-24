package runner

import (
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/acme/scan-to-tally/connector/internal/protocol"
	"github.com/acme/scan-to-tally/connector/internal/tally"
)

func TestResolveAlterTargetRequiresOwnership(t *testing.T) {
	for _, tc := range []struct {
		name, marker, narration, kind string
		allowed                       bool
	}{
		{"ours without remote id", "[STT:original#0]", "Received [STT:original#0]", "Physical Stock", true},
		{"missing marker", "", "Received [STT:original#0]", "Physical Stock", false},
		{"wrong marker", "[STT:other#0]", "Received [STT:original#0]", "Physical Stock", false},
		{"wrong type", "[STT:original#0]", "Received [STT:original#0]", "Sales", false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
				fmt.Fprintf(w, `<ENVELOPE><VOUCHER><MASTERID>72</MASTERID><DATE>20260924</DATE><VOUCHERTYPENAME>%s</VOUCHERTYPENAME><NARRATION>%s</NARRATION></VOUCHER></ENVELOPE>`, tc.kind, tc.narration)
			}))
			defer srv.Close()
			r := &Runner{tc: tally.NewClient(tally.Config{BaseURL: srv.URL, Company: "ACME", Timeout: time.Second}, nil)}
			id, err := r.resolveAlterTarget(context.Background(), protocol.PostVoucherJob{AlterMasterID: "72", AlterMarker: tc.marker, AlterDate: time.Now()})
			if tc.allowed {
				if err != nil || id.MasterID != "72" || id.Date != "20260924" {
					t.Fatalf("identity=%+v err=%v", id, err)
				}
			} else if err == nil {
				t.Fatal("allowed an unverified voucher")
			}
		})
	}
}
