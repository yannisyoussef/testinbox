# TestInbox SMTP edge — rehearsal image.
#
# Ubuntu 24.04 because that is what the real Contabo edge runs, and it ships
# Postfix 3.8.6 — the same version. The behaviours this rehearsal proves are
# version-sensitive (local_header_rewrite_clients defaults, the zero-length
# *_notice_recipient fatal, anvil accounting), so a different base would be
# testing a different MTA.
#
# This image is the REHEARSAL edge. The real host runs Postfix natively, because
# host firewall semantics are load-bearing there; that is an Ops concern and is
# not reproduced here. What is reproduced is the configuration family and every
# behaviour the application owns.
FROM ubuntu:24.04

ENV DEBIAN_FRONTEND=noninteractive

# postfix-pcre is not required by the contract, but rsyslog deliberately is not
# installed: Postfix 3.8 logs through postlogd to maillog_file, which keeps the
# container to one process tree and makes the log a file the tests can read.
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      postfix \
      ca-certificates \
      python3 \
      netcat-openbsd \
 && rm -rf /var/lib/apt/lists/* \
 && postconf -d mail_version

# The unprivileged, shell-less account postmaster@ is aliased to. Ops runs the
# equivalent on the real host; it exists here so the local: disposition is a
# real delivery rather than a simulated one.
RUN useradd --create-home --shell /usr/sbin/nologin edgepm \
 && mkdir -p /home/edgepm/Maildir/new /home/edgepm/Maildir/cur /home/edgepm/Maildir/tmp \
 && chown -R edgepm:edgepm /home/edgepm/Maildir

COPY deploy/mail-edge/contract.yaml deploy/mail-edge/contract.py /opt/mail-edge/
COPY deploy/mail-edge/templates/ /opt/mail-edge/templates/
COPY deploy/mail-edge/render.sh /opt/mail-edge/
COPY deploy/mail-edge/entrypoint.sh /opt/mail-edge/
RUN chmod +x /opt/mail-edge/render.sh /opt/mail-edge/entrypoint.sh /opt/mail-edge/contract.py

EXPOSE 25

ENTRYPOINT ["/opt/mail-edge/entrypoint.sh"]
