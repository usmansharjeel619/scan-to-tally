package runner

import (
	"github.com/acme/scan-to-tally/connector/internal/protocol"
	"github.com/acme/scan-to-tally/connector/internal/tally"
	"testing"
)

func TestRetainedBoxesUseTallyGodownCasingButExactBoxIdentity(t *testing.T) {
	pj := protocol.PostVoucherJob{Godown: "Main location", RetainedBoxes: []string{"Box-A"}, Lines: []protocol.Line{{StockItemName: "ITEM"}}}
	history := []tally.VoucherHistory{{MasterID: "127", Batches: []tally.HistoryBatch{{Item: "ITEM", Godown: "Main Location", Box: "Box-A"}}}}
	if !retainedBoxesPresent(history, pj, "127") {
		t.Fatal("capitalisation must not imply a deleted box")
	}
	if retainedBoxesPresent(history, pj, "128") {
		t.Fatal("wrong voucher matched")
	}
	for _, change := range []string{"box", "item", "godown"} {
		h := []tally.VoucherHistory{{MasterID: "127", Batches: []tally.HistoryBatch{{Item: "ITEM", Godown: "Main Location", Box: "Box-A"}}}}
		switch change {
		case "box":
			h[0].Batches[0].Box = "box-a"
		case "item":
			h[0].Batches[0].Item = "OTHER"
		case "godown":
			h[0].Batches[0].Godown = "Other Store"
		}
		if retainedBoxesPresent(h, pj, "127") {
			t.Fatalf("matched wrong %s", change)
		}
	}
}
