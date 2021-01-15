#!/bin/bash

if [ "$#" -ne 2 ]; then
    echo "Expected disk image and seed image as argument"
    exit 1
fi

FILENAME=Fedora-Cloud-Base-33-1.2.x86_64.qcow2
wget -q https://download.fedoraproject.org/pub/fedora/linux/releases/33/Cloud/x86_64/images/$FILENAME
qemu-img create -f qcow2 -b $FILENAME $1 20G
genisoimage -output $2 -volid cidata -joliet -rock ./.github/config/user-data ./.github/config/meta-data