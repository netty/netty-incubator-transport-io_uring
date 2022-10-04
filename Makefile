.PHONY: set-arch build run-build shell
.DEFAULT_GOAL := run-build

arch=$(shell uname -m)
set-arch:
ifeq ($(arch),aarch64)
	arch=arm64
endif

build: set-arch
ifeq ($(arch),arm64)
	docker-compose -f docker/docker-compose.centos-7arm.yaml build
else
	docker-compose -f docker/docker-compose.centos-6.yaml -f docker/docker-compose.centos-6.11.yaml build
endif

run-build: build
ifeq ($(arch),arm64)
	docker-compose -f docker/docker-compose.centos-7arm.yaml run compile-aarch64-build
else
	docker-compose -f docker/docker-compose.centos-6.yaml -f docker/docker-compose.centos-6.11.yaml run build-leak
endif

shell: build
ifeq ($(arch),arm64)
	docker-compose -f docker/docker-compose.centos-7arm.yaml run compile-aarch64-shell
else
	docker-compose -f docker/docker-compose.centos-6.yaml -f docker/docker-compose.centos-6.11.yaml run shell
endif
