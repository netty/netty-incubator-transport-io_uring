#!/bin/bash
# ----------------------------------------------------------------------------
# Copyright 2021 The Netty Project
#
# The Netty Project licenses this file to you under the Apache License,
# version 2.0 (the "License"); you may not use this file except in compliance
# with the License. You may obtain a copy of the License at:
#
#   https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
# WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
# License for the specific language governing permissions and limitations
# under the License.
# ----------------------------------------------------------------------------
set -e

if [ "$#" -ne 1 ]; then
    echo "Expected kernel image as argument"
    exit 1
fi

KERNEL=linux-5.9.16
wget -q https://cdn.kernel.org/pub/linux/kernel/v5.x/$KERNEL.tar.xz
tar xf $KERNEL.tar.xz
cd $KERNEL && cp /boot/config-`uname -r`* .config
make ARCH=x86_64 olddefconfig
make -j$(nproc)
cp arch/x86/boot/bzImage $1
