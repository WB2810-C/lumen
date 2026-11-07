#!/bin/bash
# Switches the TV back to the normal Google TV home screen (and can remove Lumen).
exec "$(dirname "$0")/install-lumen.command" restore
