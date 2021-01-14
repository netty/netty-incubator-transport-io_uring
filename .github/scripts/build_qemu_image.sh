#!/bin/bash
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
