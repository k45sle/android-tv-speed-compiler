.PHONY: install check test lint package docker-check smoke

install:
	uv sync --locked --all-groups

check: lint test package

lint:
	uv run --locked ruff check .

test:
	uv run --locked pytest -q

package:
	rm -rf dist build *.egg-info src/*.egg-info
	uv build --no-sources
	uv run --locked python scripts/check_package.py dist/*.whl dist/*.tar.gz

docker-check:
	docker compose config --quiet
	docker compose -f compose.linux-host.yaml config --quiet

smoke:
	PLAYWRIGHT_MODULE="$${PLAYWRIGHT_MODULE:?set PLAYWRIGHT_MODULE to an installed Playwright package}" uv run --locked python scripts/smoke_browser.py
