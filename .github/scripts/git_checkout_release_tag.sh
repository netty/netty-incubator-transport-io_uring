#!/bin/bash
set -e

if [ "$#" -ne 1 ]; then
    echo "Expected release.properties file"
    exit 1
fi

TAG=`cat $1 | grep scm.tag= | cut -d'=' -f2`

echo "Checkout tag $TAG"
git checkout $TAG
exit 0
