#!/usr/bin/env bash
# Builds the AUR package from the current checkout inside an Arch container
# and runs namcap over the result. Run it from anywhere:
#
#   docker run --rm -v "$PWD:/repo:ro" archlinux:base-devel /repo/packaging/aur/test.sh
set -euo pipefail

repo=/repo
pacman -Syu --noconfirm --needed git namcap >/dev/null

useradd -m builder
echo 'builder ALL=(ALL) NOPASSWD: ALL' >/etc/sudoers.d/builder
work=/home/builder/pkg
install -d -o builder "$work"
cp "$repo"/packaging/aur/{PKGBUILD,varchd.install} "$work/"

# makepkg uses a source file that is already next to the PKGBUILD, so the
# checkout stands in for the tagged archive GitHub would serve.
pkgver=$(sed -n 's/^pkgver=\([^ ]*\).*/\1/p' "$work/PKGBUILD")
git -c safe.directory='*' -C "$repo" archive --prefix="VarchController-$pkgver/" \
  -o "$work/varchd-$pkgver.tar.gz" HEAD
chown -R builder "$work"

cd "$work"
sudo -u builder makepkg --syncdeps --noconfirm
sudo -u builder makepkg --printsrcinfo >/dev/null
namcap PKGBUILD
namcap ./*.pkg.tar.zst
pacman -Qlp ./*.pkg.tar.zst
