.PHONY: dev start stop restart logs clean package

# Build the JAR and start all services in the foreground.
dev: package
	docker compose up --force-recreate

# Build the JAR and start all services detached (background).
start: package
	docker compose up --force-recreate -d
	@echo ""
	@echo "  Admin UI  → http://localhost:8080/auth/admin  (admin / admin)"
	@echo "  Mailhog   → http://localhost:8025"
	@echo ""
	@echo "Run 'make logs' to tail Keycloak output."

# Stop and remove containers (keeps volumes).
stop:
	docker compose down

# Stop, rebuild the JAR, and restart.
restart: stop dev

# Tail Keycloak logs.
logs:
	docker compose logs -f keycloak

# Stop containers, remove volumes, and clean Maven build output.
clean:
	docker compose down -v
	mvn clean

# Build the extension JAR without running tests.
package:
	mvn package -DskipTests
