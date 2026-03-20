# Default task
.PHONY: all
all: clean build

# Run unit tests
.PHONY: test
test:
	@./gradlew --rerun-tasks allTests

# Clean the project
.PHONY: clean
clean:
	./gradlew clean

# Format the code
.PHONY: build
build:
	./gradlew build

# Publish to Maven Local
.PHONY: publish
publish:
	rm -rf ~/.m2/repository/me/kpavlov/javable
	./gradlew publishToMavenLocal


