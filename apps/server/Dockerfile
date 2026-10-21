# AnVề API server.
#
# Two stages: the builder compiles a static binary, the runtime image carries
# only the binary and the CA certificates. The runtime image runs as a
# non-root user on a read-only root filesystem.

FROM golang:1.27.3-alpine@sha256:f2c872bcee949b7be9c490efd02b48a8a2fbf947cdf533f1c58525ec9d8170fd AS builder

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

FROM alpine:3.22@sha256:5291449c3df73caf6ed85e649dec1b9e818b39a5d8c871e97afc13e9cd5e8fa8

RUN apk add --no-cache ca-certificates tzdata wget \
 && addgroup -S anve \
 && adduser -S -G anve anve

COPY --from=builder /out/api /usr/local/bin/api
COPY --from=builder /out/migrate /usr/local/bin/migrate

USER anve
WORKDIR /app

EXPOSE 8080

ENTRYPOINT ["/usr/local/bin/api"]
