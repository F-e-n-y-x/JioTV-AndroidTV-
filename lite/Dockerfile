# jtv-lite in Docker. Uses Go's own HTTPS client (-tags nettls): scratch has no curl.
FROM golang:1.27-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY *.go ui.html ./
ARG VERSION=docker
RUN CGO_ENABLED=0 go build -tags nettls -trimpath -ldflags "-s -w -X main.version=$VERSION" -o /jtv-lite . \
 && mkdir -p /data

FROM scratch
COPY --from=build /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/
COPY --from=build /jtv-lite /jtv-lite
COPY --from=build --chown=65534:65534 /data /data
USER 65534:65534
ENV JTV_DATA=/data/jtv-lite.json SERVER_NAME="JTV lite"
EXPOSE 29180
ENTRYPOINT ["/jtv-lite"]
