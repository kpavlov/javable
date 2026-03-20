# Default task
.PHONY: all
all: clean build apidocs

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

# Format the code
.PHONY: apidocs
apidocs:
	./gradlew dokkaGenerateHtml

# Publish to Maven Local
.PHONY: publish
publish:
	rm -rf ~/.m2/repository/me/kpavlov/javable
	./gradlew publishToMavenLocal


