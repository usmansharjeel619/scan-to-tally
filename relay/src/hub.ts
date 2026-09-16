/**
 * The connector side of the relay.
 *
 * Connectors dial IN over a WebSocket and hold it open. Nothing ever dials out
 * to them, which is the whole reason this architecture exists: Tally's gateway
 * has no authentication, so it must never be reachable from a network, and an
 * outbound connection means no inbound port, no firewall exception and no
 * static IP on the warehouse machine.
 */

import type { WebSocket } from 'ws';
import type { DB } from './db.ts';
import { audit, nowIso } from './db.ts';

export type Health = 'ONLINE' | 'BUSY' | 'COMPANY_CLOSED' | 'OFFLINE' | 'UNKNOWN';

export interface ConnectorState {
  connectorId: string;
  company: string;
  hostname: string;
  version: string;
  health: Health;
  lastSeen: string | null;
  lastError: string;
  queuedJobs: number;
  failedJobs: number;
  connectedAt: string;
}

interface Conn {
  socket: WebSocket;
  state: ConnectorState;
}

export interface JobResult {
  sessionId: string;
  jobId?: string;
  ok: boolean;
  tallyVoucherId?: string;
  duplicate?: boolean;
  errorClass?: string;
  errorCode?: string;
  errorMessage?: string;
  attempts?: number;
}

export interface DiagResponse {
  id: string;
  ok: boolean;
  xml?: string;
  error?: string;
  bytes?: number;
  millis?: number;
}

type ResultHandler = (r: JobResult) => void;
type SyncHandler = (company: string, payload: unknown) => void;

export class ConnectorHub {
  private conns = new Map<string, Conn>();
  /**
   * Phase 0 diagnostic queries awaiting an answer from the connector.
   *
   * Every entry carries its own timeout so a connector that goes away
   * mid-query cannot leave a request hanging forever.
   */
  private pendingDiag = new Map<string, {
    resolve: (r: DiagResponse) => void;
    timer: NodeJS.Timeout;
  }>();
  private onResult: ResultHandler;
  private onSync: SyncHandler;
  private db: DB;

  constructor(db: DB, onResult: ResultHandler, onSync: SyncHandler) {
    this.db = db;
    this.onResult = onResult;
    this.onSync = onSync;
  }

  /** Attaches a freshly authenticated connector socket. */
  register(socket: WebSocket): void {
    let id = '';

    socket.on('message', (data: Buffer) => {
      let frame: { type?: string; id?: string; payload?: any };
      try {
        frame = JSON.parse(data.toString());
      } catch {
        return; // a malformed frame is not worth dropping the connection over
      }

      switch (frame.type) {
        case 'hello': {
          const p = frame.payload ?? {};
          id = String(p.connectorId ?? '');
          if (!id) return;
          this.conns.set(id, {
            socket,
            state: {
              connectorId: id,
              company: String(p.company ?? ''),
              hostname: String(p.hostname ?? ''),
              version: String(p.version ?? ''),
              health: 'UNKNOWN',
              lastSeen: null,
              lastError: '',
              queuedJobs: 0,
              failedJobs: 0,
              connectedAt: nowIso(),
            },
          });
          audit(this.db, `connector:${id}`, 'CONNECTED', p.company ?? '', p.hostname ?? '');
          socket.send(JSON.stringify({ type: 'hello_ack', payload: { ok: true } }));
          // A connector that has just reconnected may have missed jobs while it
          // was away, so hand it everything still outstanding.
          this.onSync(String(p.company ?? ''), { resend: true });
          break;
        }

        case 'heartbeat': {
          const c = this.conns.get(id);
          if (!c) return;
          const p = frame.payload ?? {};
          c.state.health = (p.health ?? 'UNKNOWN') as Health;
          c.state.lastSeen = p.lastSeen ?? nowIso();
          c.state.lastError = String(p.lastError ?? '');
          c.state.queuedJobs = Number(p.queuedJobs ?? 0);
          c.state.failedJobs = Number(p.failedJobs ?? 0);
          break;
        }

        case 'job_result':
          this.onResult(frame.payload as JobResult);
          break;

        case 'diag_res': {
          const res = frame.payload as DiagResponse;
          const waiting = this.pendingDiag.get(res.id);
          if (waiting) {
            clearTimeout(waiting.timer);
            this.pendingDiag.delete(res.id);
            waiting.resolve(res);
          }
          break;
        }

        case 'sync_push': {
          const c = this.conns.get(id);
          this.onSync(c?.state.company ?? '', frame.payload);
          break;
        }
      }
    });

    const drop = () => {
      if (id && this.conns.get(id)?.socket === socket) {
        this.conns.delete(id);
        audit(this.db, `connector:${id}`, 'DISCONNECTED');
      }
      for (const [qid, waiting] of this.pendingDiag) {
        clearTimeout(waiting.timer);
        waiting.resolve({ id: qid, ok: false, error: 'The connector disconnected mid-query.' });
      }
      this.pendingDiag.clear();
    };
    socket.on('close', drop);
    socket.on('error', drop);
  }

  /** Sends a job to the connector fronting this company. */
  dispatch(company: string, jobId: string, job: unknown): boolean {
    const conn = this.forCompany(company);
    if (!conn) return false;
    try {
      conn.socket.send(JSON.stringify({ type: 'job', id: jobId, payload: job }));
      return true;
    } catch {
      return false;
    }
  }

  /**
   * Runs a READ-ONLY Tally query on the connector. Phase 0 only.
   *
   * The connector refuses anything that is not an Export, and refuses
   * everything unless diagnostics are explicitly enabled in its config -- so
   * this cannot modify data even if the relay is compromised.
   */
  diag(company: string, xml: string, label = '', timeoutMs = 130_000): Promise<DiagResponse> {
    const conn = this.forCompany(company);
    const id = `diag-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;

    if (!conn) {
      return Promise.resolve({ id, ok: false, error: 'No connector is connected to the relay.' });
    }

    return new Promise<DiagResponse>((resolve) => {
      const timer = setTimeout(() => {
        this.pendingDiag.delete(id);
        resolve({ id, ok: false, error: `Timed out after ${timeoutMs}ms waiting for the connector.` });
      }, timeoutMs);

      this.pendingDiag.set(id, { resolve, timer });
      try {
        conn.socket.send(JSON.stringify({
          type: 'diag_req', payload: { id, xml, label },
        }));
      } catch (e) {
        clearTimeout(timer);
        this.pendingDiag.delete(id);
        resolve({ id, ok: false, error: `Could not reach the connector: ${String(e)}` });
      }
    });
  }

  private forCompany(company: string): Conn | undefined {
    for (const c of this.conns.values()) {
      if (c.state.company === company) return c;
    }
    // Single-site deployments have exactly one connector; don't make them
    // configure a company name just to find it.
    return this.conns.size === 1 ? [...this.conns.values()][0] : undefined;
  }

  /**
   * What the device's status bar shows. Reported honestly -- operators
   * tolerate delay, they do not tolerate not knowing.
   */
  status(company?: string): ConnectorState | { health: Health; lastError: string } {
    const conn = company ? this.forCompany(company) : [...this.conns.values()][0];
    if (!conn) {
      return { health: 'OFFLINE', lastError: 'No connector is connected to the relay.' };
    }
    return conn.state;
  }

  all(): ConnectorState[] {
    return [...this.conns.values()].map((c) => c.state);
  }
}
