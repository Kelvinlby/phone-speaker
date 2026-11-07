CC ?= cc
CFLAGS ?= -O2 -Wall -Wextra -D_FORTIFY_SOURCE=2 -fstack-protector-strong
LDFLAGS ?= -s -Wl,-z,relro,-z,now

BIN_DIR := $(HOME)/.local/bin
UNIT_DIR := $(HOME)/.config/systemd/user

phone-speakerd: phone-speakerd.c
	$(CC) $(CFLAGS) -o $@ $< $(LDFLAGS)

install: phone-speakerd
	install -Dm755 phone-speakerd $(BIN_DIR)/phone-speakerd
	install -Dm644 phone-speakerd.service $(UNIT_DIR)/phone-speakerd.service
	systemctl --user daemon-reload
	systemctl --user enable phone-speakerd.service
	systemctl --user restart phone-speakerd.service

uninstall:
	-systemctl --user disable --now phone-speakerd.service
	rm -f $(BIN_DIR)/phone-speakerd $(UNIT_DIR)/phone-speakerd.service
	systemctl --user daemon-reload

clean:
	rm -f phone-speakerd

.PHONY: install uninstall clean
