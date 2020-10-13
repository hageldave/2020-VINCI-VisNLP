#!/bin/sh
MAIN_CLASS="hageldave.visnlp.app.Application" mvn clean install assembly:single
cp target/visnlp-0.0.1-SNAPSHOT-jar-with-dependencies.jar visnlp.jar
