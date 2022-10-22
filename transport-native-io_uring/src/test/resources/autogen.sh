#!/bin/sh
# ---------------------------------------------------------------------------
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
# 
# http://www.apache.org/licenses/LICENSE-2.0
# 
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
# ---------------------------------------------------------------------------

auto_clean() {
  AUTO_FILES="
  configure config.log config.status 
  autom4te.cache autotools aclocal.m4  libtool  
  m4/libtool.m4 m4/ltoptions.m4 m4/ltsugar.m4 m4/ltversion.m4 m4/lt~obsolete.m4 
  Makefile.in Makefile 
  src/Makefile src/Makefile.in  
  src/config.in src/config.h src/config.h.in* src/stamp-h1
  "
  for f in "$AUTO_FILES" ; do
    rm -Rf $f
  done
}
auto_reconf() {  
  autoreconf --force --install -I m4  
}

case "$1" in
  clean)    echo "auto clean..."
            auto_clean
  ;;
  *)        echo "auto reconf..."
            auto_clean
            auto_reconf
  ;;
esac
