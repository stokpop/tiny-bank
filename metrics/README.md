# tiny-bank metrics

Starts InfluxDB, Grafana, Prometheus and otel-collector.

Adds basic dashboards to Grafana. Goto `http://localhost:3000` and login with `admin`/`admin`.

Created database and adds continuous queries to InfluxDB.

The otel-collector scrapes the tiny-bank metrics and sends it to Prometheus.

To start:

    docker compose up -d

To stop:

    docker compose down

To stop and remove persistent volumes:

    docker compose down -v

