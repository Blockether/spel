.PHONY: test test-cli test-cli-clj test-watch test-junit test-allure allure-serve allure \
	clean jar install deploy lint repl format \
	spel uberjar install-local gen-docs init-agents validate-safe-graal

REPL_PORT ?= 7600

# =============================================================================
# Testing
# =============================================================================

test: ## ALL tests: Clojure (lazytest) + CLI bash regression
	clojure -M:test
	./test-cli.sh

test-cli: ## CLI bash regression tests only
	./test-cli.sh

test-cli-clj: ## CLI Clojure integration tests only
	clojure -M:test -n com.blockether.spel.cli-integration-test

test-watch: ## Watch mode (re-runs on file change)
	clojure -M:test --watch

test-junit: ## Tests with JUnit XML output (test-results/junit.xml)
	clojure -M:test --output nested --output com.blockether.spel.junit-reporter/junit

test-allure: ## Tests + Allure 3 HTML report (allure-results/ + allure-report/)
	clojure -M:test --output nested --output com.blockether.spel.allure-reporter/allure

allure-serve: ## Open Allure report in browser
	npx http-server allure-report -o -p 9999

allure: test-allure allure-serve ## Tests → report → open in browser

# =============================================================================
# Quality
# =============================================================================

format: ## Auto-format all source files
	clojure-lsp format

lint: ## Diagnostics via clojure-lsp (includes clj-kondo)
	clojure-lsp diagnostics --raw

validate-safe-graal: ## Check src for GraalVM native-image safety (reflection/boxed math)
	@echo "Checking root src for GraalVM native-image safety..."
	@clojure \
		-e "(set! *warn-on-reflection* true)" \
		-e "(set! *unchecked-math* :warn-on-boxed)" \
		-e "(doseq [^java.io.File f (file-seq (clojure.java.io/file \"src\"))] (when (and (.isFile f) (.endsWith (.getName f) \".clj\")) (try (load-file (.getPath f)) (catch Exception e (println \"Error loading:\" (.getPath f) (.getMessage e))))))" \
		2>&1 | grep -iE "(reflection|boxed math) warning" | grep "/src/" && { echo "=== FAILED: Reflection/boxed math warnings found ==="; exit 1; } || echo "=== GraalVM Check Passed ==="

# =============================================================================
# Build
# =============================================================================

jar: ## Build library JAR
	clojure -T:build jar

uberjar: ## Build uberjar for native-image compilation
	clojure -T:build uberjar

spel: ## Build native 'spel' binary (requires GraalVM)
	clojure -T:build native-image

install-local: spel ## Build + install spel binary to ~/.local/bin
	@mkdir -p $(HOME)/.local/bin
	rm -f $(HOME)/.local/bin/spel
	cp target/spel $(HOME)/.local/bin/spel

gen-docs: ## Regenerate SKILL.md from template (run BEFORE install-local)
	clojure -T:build gen-docs

init-agents: ## Scaffold OpenCode E2E testing agents (via spel)
	./target/spel init-agents $(ARGS)

# =============================================================================
# Publish
# =============================================================================

install: ## Install JAR to local Maven repo
	clojure -T:build install

deploy: ## Deploy JAR to Clojars
	clojure -T:build deploy

# =============================================================================
# Dev
# =============================================================================

clean: ## Clean build artifacts
	clojure -T:build clean
	rm -rf .cpcache .clj-kondo/.cache

repl: ## Start nREPL on port $(REPL_PORT)
	@echo "Starting spel REPL on port $(REPL_PORT)..."
	@echo "  - Playwright Java 1.58.0 on classpath"
	@echo "  - Test sources included"
	@echo "  - Connect: clj-nrepl-log -p $(REPL_PORT)"
	@echo ""
	@clj -M:dev \
		-e "(require 'nrepl.cmdline)" \
		-e "(nrepl.cmdline/-main \"--port\" \"$(REPL_PORT)\" \"--middleware\" \"[cider.nrepl/cider-middleware]\" \"--interactive\")"
