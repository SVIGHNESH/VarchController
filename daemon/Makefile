BIN := $(HOME)/.local/bin/varchd
UNIT := $(HOME)/.config/systemd/user/varchd.service

VERSION := $(shell sed -n 's/.*version *= *"\(.*\)".*x-release-please-version.*/\1/p' server.go)
ARCHES := amd64 arm64
DIST := ../dist

.PHONY: build test install uninstall dist

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

# Release tarballs, one per architecture, in $(DIST).
# The binaries are static and carry no build paths, so the same commit gives the same bytes.
dist:
	@set -e; for arch in $(ARCHES); do \
		name=varchd-$(VERSION)-linux-$$arch; \
		rm -rf $(DIST)/$$name; mkdir -p $(DIST)/$$name; \
		CGO_ENABLED=0 GOOS=linux GOARCH=$$arch go build -trimpath -ldflags='-s -w' -o $(DIST)/$$name/varchd .; \
		cp varchd.service ../LICENSE ../README.md $(DIST)/$$name/; \
		tar -C $(DIST) --sort=name --owner=0 --group=0 --numeric-owner --mtime=@$${SOURCE_DATE_EPOCH:-0} \
			-czf $(DIST)/$$name.tar.gz $$name; \
		rm -rf $(DIST)/$$name; \
		echo $(DIST)/$$name.tar.gz; \
	done
