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

if [ "$#" -ne 5 ]; then
    echo "Expected qemu dir, disk image and seed image as argument"
    exit 1
fi

FILENAME=Fedora-Cloud-Base-33-1.2.x86_64.qcow2
cp $4 $1/user-data
cp $5 $1/meta-data

cd $1
wget -q https://download.fedoraproject.org/pub/fedora/linux/releases/33/Cloud/x86_64/images/$FILENAME
qemu-img create -f qcow2 -b $FILENAME $1/$2 20G
genisoimage -output $1/$3 -volid cidata -joliet -rock $1/meta-data $1/user-data
