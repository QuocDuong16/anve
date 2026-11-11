# AnVề API server.
#
# Two stages: the builder compiles a static binary, the runtime image carries
# only the binary and the CA certificates. The runtime image runs as a
# non-root user on a read-only root filesystem.

FROM golang:1.27.4-alpine@sha256:0a5f7fe796599ff487605987432e7619ae45beeb2fba4c2ce0b907879932aebf AS builder

WORKDIR /src

# Download the module dependencies first so that source-only changes reuse
# the cached layer.
COPY go.mod go.sum ./
RUN go mod download

COPY . .

ARG VERSION=dev
RUN CGO_ENABLED=0 GOOS=linux go build \
    -trimpath \
    -ldflags "-s -w -X main.version=${VERSION}" \
    -o /out/api ./cmd/api \
 && CGO_ENABLED=0 GOOS=linux go build \
    -trimpath \
    -ldflags "-s -w" \
    -o /out/migrate ./cmd/migrate

FROM alpine:3.24@sha256:294b683cb724975bec92580e1e685676bd4b50bda910ddb8c51d4cabeaec77e6

RUN apk add --no-cache ca-certificates tzdata wget \
 && addgroup -S anve \
 && adduser -S -G anve anve

COPY --from=builder /out/api /usr/local/bin/api
COPY --from=builder /out/migrate /usr/local/bin/migrate

USER anve
WORKDIR /app

EXPOSE 8080

ENTRYPOINT ["/usr/local/bin/api"]
