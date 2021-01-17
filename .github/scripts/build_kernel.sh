#!/bin/bash
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
