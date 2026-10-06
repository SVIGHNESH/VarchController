BIN := $(HOME)/.local/bin/varchd
UNIT := $(HOME)/.config/systemd/user/varchd.service

.PHONY: build test install uninstall

build:
	go build -o varchd .

test:
	go vet ./...
	go test -race ./...

install: build
	install -Dm755 varchd $(BIN)
	install -Dm644 varchd.service $(UNIT)
	systemctl --user daemon-reload
	systemctl --user enable varchd.service
	systemctl --user restart varchd.service

uninstall:
	-systemctl --user disable --now varchd.service
	rm -f $(BIN) $(UNIT)
	systemctl --user daemon-reload
