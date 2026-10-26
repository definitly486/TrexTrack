#!/bin/sh
dir=$(dirname "$(realpath $0)")
cd $dir
git add --all
git add .
git commit -n
git push ssh://git@github.com/definitly486/TrexTrack