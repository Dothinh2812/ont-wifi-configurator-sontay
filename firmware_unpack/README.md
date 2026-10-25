# Unpack `tclinux.bin.signed` (EcoNet/Airoha EN7523 ONT firmware)

Target: `../tclinux.bin.signed.G040E5VN0T0204_260825_173514` (16 105 423 B)

## Kết quả

```
[HDR2] name='G040E5VN0T0204' flags=0x100 payload=0xF5BF6B crc32=0x921C604A
       tag='000_010_000_000' version='3 6035 122 0'
[FIT ] kernel@1      lzma      2 688 487 B -> 7 376 896 B  (sha1 OK)
[FIT ] filesystem@1  squashfs 13 407 093 B (lzma blocks,   (sha1 OK)
                                        1258 inodes, 128K block)
[FIT ] fdt@1         flat_dt   7 961 B  econet,en7523       (sha1 OK)
```

## Định dạng container

Toàn bộ file bị **đảo bit (XOR 0xFF)**; chỉ cần NOT là đọc được ngay.

| Offset | Nội dung |
|---|---|
| 0x0000 | header `HDR2`, 256 B: magic, flags `0x100`, `payload_size` (LE), `crc32 = ~crc32(FIT)`, tên image, tag HW/SW, version |
| 0x0100 | FIT image (uImage.itb, FDT `0xd00dfeed`, size 0xF5BE6B) chứa 3 image dạng `data` inline + `sha1` của mỗi image |
| | `/images/kernel@1` — LZMA (`.lzma` FORMAT_ALONE), load/entry `0x80088000` |
| | `/images/filesystem@1` — SquashFS 4.0 little-endian, block 128 KiB, compressor LZMA (id 2) |
| | `/images/fdt@1` — DTB `econet,en7523` (Cortex-A53 ×2 ở AArch32, GICv3, XPON, PCIe, NPU @0x80000000, burn bootloader) |
| EOF−100 | trailer: `@CSC` + base64(chữ ký 64 B, ECDSA-P256) + u32 BE `100` + `@CSC` |

Bootargs trong DTB: `root=/dev/mtdblock3 ro console=ttyS0,115200n8 earlycon init=/sbin/init`.
Kernel: `Linux version 4.4.115 (root@root) gcc 4.9.3 (Buildroot 2015.08.1) #1 SMP Tue Aug 25 17:35:14 ICT 2026`.

## Dùng

```bash
python3 unpack_tclinux.py <firmware|dir> [out]   # NOT + parse HDR2 + tách FIT, verify sha1
unsquashfs -d out/rootfs -no-progress out/filesystem@1.squashfs   # exit 2 là bình thường: /dev/* cần sudo
```

`out/rootfs` — 53 MB userland: busybox + uClibc-ng/glibc 2.22, boa (port 80, `/boaroot/html` + 80 CGI ASP),
`/userfs/bin` có `omci`, `tr69`, `ponmgr`, `wifimgr`, `dropbear`, `tftpd`, `bftpd`, `vnptt_*` (binary riêng của VNPT),
`/lib/modules` (xpon, gpon_flow, mt7916_ap, hw_nat...). `/etc -> /tmp/etc`, config gốc ở `usr/etc` (85 file) và
`userfs/ctromfile.cfg` (gzip → tar → `HDR3` 256 B header + body AES-256-CBC key tĩnh — xem mục dưới).

Định danh: `usr/etc/fwver.conf` = `G040E5VN0T0204`, `usr/etc/app_fwver.conf` = `7.3.267.700_v080`,
`usr/etc/passwd` = `admin:$1$$iC.dUsGpxNNJGeOm1dFio/:0:0:root:/:/bin/sh` (md5-crypt),
`usr/etc/init.d/rcS` copy `/usr/etc` → `/tmp/etc` rồi nạp `TCSUPPORT_*` profile.

## Giải romfile mặc định (`ctromfile.cfg`)

`userfs/ctromfile.cfg` = gzip → tar → `HDR3` (256 B header) → **AES-256-CBC**.

Key/IV tĩnh, không phải per-device: `sys_romfile_legacy_aes_get()` trong `lib/libcfg_clisvc.so`
dựng chúng từ bảng rodata 48 B tại `+0x827D0` (bản `7.3.267.700_v080`):

```
key[i] = ~(table[i]      ^ 0x5A)   i in [0,32)
iv[i]  =  (table[32 + i] ^ 0x5A)   i in [0,16)
key = '/RSl8mMp70sUJPjxA2zTw1R7rru/ng=='   iv = '509z0VvAt0WE/W7E'
```

```bash
python3 decrypt_romfile.py out/rootfs/userfs/ctromfile.cfg   # -> ctromfile.decrypted.xml
```

Romfile backup do thiết bị sinh (`encrypted_data_agent`, PKCS#7/RSA, key per-device) thì **không** giải offline được.

Cấu trúc mã hoá (từ `vnptt_aes_crypt_file` trong cùng lib): plaintext chia **record 1024 B**, mỗi record
được AES-256-CBC **độc lập** với **cùng một key + IV tĩnh** rồi PKCS#7 pad → 1040 B trên đĩa. Hệ quả:

- Giải bằng 1 mạch CBC liên tục sẽ mất 1 block/record (32 B rác mỗi 1040 B, 3 % file) — `decrypt_romfile.py`
  giải theo từng record nên thu đủ 32 753 B, không mất byte nào.
- IV lặp lại + key tĩnh nhúng trong firmware ⇒ **không có tính bảo mật thật**: record giống nhau cho ciphertext
  giống nhau, và ai có firmware là giải được romfile của mọi máy cùng model.

### Tài khoản factory (từ romfile đã giải)

| Dịch vụ | Cổng | User / pass | Active |
|---|---|---|---|
| Telnet (`TelnetEntry`) | 23 | `admin` / `VnT3ch@dm1n` | Yes |
| Telnet (`TelnetEntry1`) | 23 | `customer` / `customer` | No |
| SSH (`SshEntry1`) | 22 | `admin` / `VnT3ch@dm1n` | Yes |
| SSH (`SshEntry2`) | 22 | `customer` / `customer` | Yes |
| Console (ttyS0 115200) | — | `admin` / `VnT3ch@dm1n` | Yes |
| FTP (`FtpEntry0`) | 21 | `admin` / `VnT3ch@dm1n` (plaintext) | — |
| Web `Entry0` / `Entry1` / `Entry2` | 80 | `admin` / `operator` / `user3` (=`1234`) | Yes |

Mật khẩu shell trong `usr/etc/passwd` (`admin`/`1234`) là account của `/bin/login` trước khi
`svc_other_handle_event_account_boot` (`lib/libother_svc.so`) ghi đè `/etc/passwd`, `/etc/userssh`,
`/etc/usertty`, `/etc/ftppasswd` từ romfile lúc boot.

Bật telnet không cần login web: `GET /cgi-bin/getGateWay.cgi` — script này in `GateWay`, `telnet_username`,
`telnet_passwd` và **ép** `Account_TelnetEntry Active=Yes` (`boa.conf` không có directive `Auth`).
SSH chỉ có node `Account_SshEntry` (không có trang web), do `libother_svc.so` start `/userfs/bin/dropbear`.

## Cấu hình qua SSH (không cần web)

Shell sau khi login là busybox `ash`, **uid 0** (`/bin/sh`), tức toàn quyền file/tiến trình. Toàn bộ web UI
chỉ là lớp mỏng trên `tcapi`: 15 file trong `boaroot/cgi-bin` gọi thẳng `tcapi`, ví dụ `getGateWay.cgi`:

```sh
tcapi set Account_TelnetEntry Active Yes && tcapi commit Account_TelnetEntry
tcapi set EPON_LOIDAuth LOID0 $sn && tcapi commit EPON_LOIDAuth
```

`userfs/bin/tcapi` (link tới `libcfg_clisvc.so` + `libtcapi.so`) export:
`tcapi_get/set/unset/commit/save/read/readAll/show` — `libcfg_clisvc.so` có **1668** node `cfg_type_*`
(gpon, xpon, omci, oam, pm, lan, wan, waninfo, wlan, wlan11ac, dhcpd, dhcp6s, radvd, lanhost, firewall, …).

Trình tự apply = trình tự web làm:

```sh
tcapi set <Node> <attr> <value>      # vào RAM (/tmp/.../romfile)
tcapi commit <Node>                  # -> /var/romfile.cfg -> mtd write ... romfile (flash)
/usr/script/<x>.sh                   # 79 script trong /usr/script, web cũng gọi chúng
```

CLI chuyên dụng (đều chạy được qua SSH, đa số in usage khi gọi sai):

| Tool | Việc |
|---|---|
| `/userfs/bin/tcapi` | đọc/ghi toàn bộ config tree (API của web) |
| `/usr/bin/prolinecmd <attr> set <v>` | `productclass`, `ManufacturerOUI`, `serialnum`, `barcode`, `ssid`, `wpakey`, `pppusrname`, `ppppwd`, `cfeusrname`, `cfepwd`, `xponsn`, `xponpwd`, `macaddr`, `xponmode`, `romfileselect` |
| `/usr/bin/sys` (`wan`, `epon`, `tce`, `tcci` là cùng 1 binary, dispatch theo argv[0]) | `sys onutype sfu\|hgu`, `sys onuMode auto_mode\|gpon\|xepon_1g\|…\|xgspon`, `sys led`, `sys button` |
| `firewallcmd`, `accesslimitcmd`, `portbindcmd`, `algcmd` | đối tượng firewall/ACL (bọc `iptables`) |
| `backuprestorecmd` | backup/restore config (`tar` + `mtd write … romfile\|reservearea`) |
| `wifimgr`, `ponmgr`, `ponmgr_cfg`, `omcicfgCmd`, `eponcmd`, `gponmapcmd`, `xponblapicmd` | WLAN / PON / OMCI |
| `iptables`, `ip6tables`, `ebtables`, `ip`, `bridge`, `brctl`, `mtd`, `insmod`, `kwctrl` | thao tác mức OS |

Giới hạn thật sự:

- Thay đổi không `commit` là mất sau reboot; một số node cần restart service hoặc **reboot** mới áp dụng.
- `vnptt_romfile_value_is_safe_for` sanitize giá trị ghi vào romfile (không nhét được shell code vào thuộc tính XML).
- TR-069 `<Cwmp_Entry Tr069Enable="1">` (ACS `http://10.149.247.147:8091`) có thể push đè cấu hình bất cứ lúc nào.
- Upgrade firmware qua SSH = `mtd writeflash <image> <size> <off> <part>` — vẫn cần **ảnh đã ký** (`@CSC`).
- Không có gì "chỉ web mới làm được": `upgrade.asp`, `backup-cfg.asp`, `mag-reset.asp`, `mag-gponpassword.asp`
  đều chỉ là form gọi CLI/script có sẵn trên máy.

## Ghi chú khi đóng gói lại
- Repack: tạo FIT (kernel `.lzma` + squashfs + dtb + sha1) → thêm `HDR2` 256 B → NOT toàn bộ → trailer `@CSC`.
- Chữ ký 64 B ở trailer: **không có private key thì không ký lại được**; bootloader EcoNet verify chữ ký này
  (và `crc32`/`sha1` trong FIT). Vì vậy xem/trích xuất thì thoải mái, flash lại cần ký hoặc bypass secure boot.
- `crc32` trong `HDR2` không phải CRC32 thường mà là **bù một (`~crc32`) của vùng FIT**.

## Nguồn

`HDR2`/`HDR3` là format của EcoNet (TrendChip) — cùng họ với các image `tclinux.bin` của EN7512/EN7523.
