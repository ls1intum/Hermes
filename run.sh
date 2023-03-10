#!/bin/bash

gradlew hermes:bootJar
docker build -t hermes .
