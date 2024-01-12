#!/bin/bash

if [ ! -d "bin" ]; then
  mkdir bin
fi

javac -d bin *.java > /dev/null
