.PHONY: test test-watch test-junit test-allure allure-report allure-serve allure clean jar install deploy lint repl \
	spel uberjar init-agents install-local

REPL_PORT ?= 7600

test:
	clojure -M:test

test-watch:
	clojure -M:test --watch

test-junit: ## Run tests with JUnit XML output (test-results/junit.xml)
	clojure -M:test --output nested --output com.blockether.spel.junit-reporter/junit

test-allure: ## Run tests with Allure report (allure-results/ + allure-report/)
	clojure -M:test --output nested --output com.blockether.spel.allure-reporter/allure

allure-serve: ## Open Allure report in browser
	npx http-server allure-report -o -p 9999

allure: test-allure allure-serve ## Run tests → generate report → open in browser

lint:
	clojure-lsp diagnostics --raw

clean:
	clojure -T:build clean
	rm -rf .cpcache .clj-kondo/.cache

jar:
	clojure -T:build jar

install:
	clojure -T:build install

deploy:
	clojure -T:build deploy

uberjar: ## Build uberjar for native-image compilation
	clojure -T:build uberjar

spel: ## Build native 'spel' binary (requires GraalVM)
	clojure -T:build native-image

repl:
	@echo "Starting spel REPL on port $(REPL_PORT)..."
	@echo "  - Playwright Java 1.58.0 on classpath"
	@echo "  - Test sources included"
	@echo "  - Connect: clj-nrepl-log -p $(REPL_PORT)"
	@echo ""
	@clj -M:dev \
		-e "(require 'nrepl.cmdline)" \
		-e "(nrepl.cmdline/-main \"--port\" \"$(REPL_PORT)\" \"--middleware\" \"[cider.nrepl/cider-middleware]\" \"--interactive\")"

format: 
	clojure-lsp format

install-local: spel ## Install spel binary to ~/.local/bin
	@mkdir -p $(HOME)/.local/bin
	rm -f $(HOME)/.local/bin/spel
	cp target/spel $(HOME)/.local/bin/spel

init-agents: ## Scaffold OpenCode E2E testing agents (via spel)
	./target/spel init-agents $(ARGS)
