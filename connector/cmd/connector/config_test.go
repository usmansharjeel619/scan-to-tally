package main

import (
	"os"
	"path/filepath"
	"testing"
)

const validConfig = `{
  "tally": {"baseUrl": "http://127.0.0.1:9000", "company": "New Test Company"},
  "relay": {"url": "wss://relay.example/connector/ws", "secret": "abc123"},
  "dbPath": "connector.db"
}`

// TestLoadConfigTolratesBOM: Windows PowerShell 5's Set-Content -Encoding UTF8
// writes a byte-order mark, and so does Notepad. Go's JSON parser rejects it
// with "invalid character 'ï'", which tells the person reading it nothing.
// This shipped once and cost a round trip to the customer's machine.
func TestLoadConfigToleratesBOM(t *testing.T) {
	for _, tc := range []struct {
		name string
		data []byte
	}{
		{"plain", []byte(validConfig)},
		{"utf8 bom", append([]byte{0xEF, 0xBB, 0xBF}, validConfig...)},
		{"leading whitespace", append([]byte("\r\n  "), validConfig...)},
		{"bom and whitespace", append([]byte{0xEF, 0xBB, 0xBF, '\r', '\n'}, validConfig...)},
	} {
		t.Run(tc.name, func(t *testing.T) {
			path := filepath.Join(t.TempDir(), "connector.json")
			if err := os.WriteFile(path, tc.data, 0o600); err != nil {
				t.Fatal(err)
			}
			cfg, err := loadConfig(path)
			if err != nil {
				t.Fatalf("loadConfig: %v", err)
			}
			if cfg.Tally.Company != "New Test Company" {
				t.Errorf("company = %q", cfg.Tally.Company)
			}
			if cfg.Relay.Secret != "abc123" {
				t.Errorf("secret = %q", cfg.Relay.Secret)
			}
		})
	}
}

// A placeholder secret must be refused rather than producing a connector that
// cannot authenticate and fails obscurely an hour later.
func TestLoadConfigRejectsPlaceholders(t *testing.T) {
	bad := `{"tally":{"company":"X"},"relay":{"url":"wss://x","secret":"CHANGE-ME"},"dbPath":"d"}`
	path := filepath.Join(t.TempDir(), "connector.json")
	os.WriteFile(path, []byte(bad), 0o600)
	if _, err := loadConfig(path); err == nil {
		t.Fatal("expected a placeholder secret to be refused")
	}
}
