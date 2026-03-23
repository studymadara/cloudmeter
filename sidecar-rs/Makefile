.PHONY: build test coverage coverage-html smoke clean

build:
	cargo build

build-release:
	cargo build --release

test:
	cargo test

coverage:
	cargo llvm-cov --summary-only

coverage-html:
	cargo llvm-cov --html
	@echo "Report written to target/llvm-cov/html/index.html"

smoke:
	../scripts/smoke-rust.sh

clean:
	cargo clean
