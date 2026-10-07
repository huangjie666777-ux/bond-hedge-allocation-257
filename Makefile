JAVA ?= java
JAVAC ?= javac

COMMONS_MATH := third_party/commons-math3-3.6.1.jar
JUNIT := third_party/junit-platform-console-standalone-1.10.3.jar

MAIN_OUT := target/classes
TEST_OUT := target/test-classes
MAIN_SOURCES := $(shell find src/main/java -name '*.java')
TEST_SOURCES := $(shell find src/test/java -name '*.java')

.PHONY: all clean compile test run

all: compile

$(MAIN_OUT):
	mkdir -p $(MAIN_OUT)

compile: $(MAIN_OUT)/.marker

$(MAIN_OUT)/.marker: $(MAIN_SOURCES) $(COMMONS_MATH) | $(MAIN_OUT)
	$(JAVAC) -encoding UTF-8 -cp $(COMMONS_MATH) -d $(MAIN_OUT) $(MAIN_SOURCES)
	touch $@

test: compile
	mkdir -p $(TEST_OUT)
	$(JAVAC) -encoding UTF-8 -cp $(MAIN_OUT):$(COMMONS_MATH):$(JUNIT) \
		-d $(TEST_OUT) $(TEST_SOURCES)
	$(JAVA) -jar $(JUNIT) execute -cp $(MAIN_OUT):$(TEST_OUT):$(COMMONS_MATH) \
		--scan-classpath --disable-banner

run: compile
	$(JAVA) -cp $(MAIN_OUT):$(COMMONS_MATH) com.hedge257.Main

clean:
	rm -rf target
