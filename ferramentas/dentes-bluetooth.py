"""Teste do composite logger da MS2/Extra pelo Bluetooth (só leitura).
Conecta, lê o tempo real e, a cada ~1,2 s, o logger 0xf2 em 4 blocos de 256.
Grava tudo em dentes-<hora>.csv (mesmo formato do app) e imprime um resumo por leitura.
Roda por DUR segundos (argv[1], padrão 150)."""
import socket, sys, time, zlib, os

MAC = "98:D3:41:F6:E0:85"
DUR = float(sys.argv[1]) if len(sys.argv) > 1 else 150
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), time.strftime("dentes-%H%M%S.csv"))


def conectar():
    t0 = time.time()
    while time.time() - t0 < 240:
        s = socket.socket(socket.AF_BLUETOOTH, socket.SOCK_STREAM, socket.BTPROTO_RFCOMM)
        s.settimeout(20)
        try:
            s.connect((MAC, 1))
            s.settimeout(2)
            return s
        except OSError as e:
            print("  conectando...", e.strerror, flush=True)
            s.close()
            time.sleep(1)
    raise SystemExit("não conectou")


def recv_exact(s, n):
    d = b""
    while len(d) < n:
        x = s.recv(n - len(d))
        if not x:
            raise ConnectionError("link caiu")
        d += x
    return d


def req(s, payload):
    s.sendall(len(payload).to_bytes(2, "big") + payload + zlib.crc32(payload).to_bytes(4, "big"))
    n = int.from_bytes(recv_exact(s, 2), "big")
    body = recv_exact(s, n)
    crc = int.from_bytes(recv_exact(s, 4), "big")
    if crc != zlib.crc32(body):
        raise ValueError("CRC")
    return body[0], body[1:]


def r(table, off, cnt):
    return b"r\x00" + bytes([table]) + off.to_bytes(2, "big") + cnt.to_bytes(2, "big")


def realtime(s):
    f, d = req(s, r(7, 0, 219))
    if f >= 0x80:
        raise ValueError("erro 0x%02x no tempo real" % f)
    return dict(rpm=int.from_bytes(d[6:8], "big"), map=int.from_bytes(d[18:20], "big", signed=True) / 10,
                bat=int.from_bytes(d[26:28], "big", signed=True) / 10, st3=d[80], sync=d[94], reason=d[139])


def logger(s, table=0xF2):
    out = b""
    for off in (0, 0x100, 0x200, 0x300):
        f, d = req(s, r(table, off, 0x100))
        if f >= 0x80:
            return None, "erro 0x%02x no bloco 0x%03x" % (f, off)
        out += d
    return out, ""


def resumo(buf):
    recs = [buf[i * 3:i * 3 + 3] for i in range(341)]
    filled = [x for x in recs if x != b"\x00\x00\x00"]
    if not filled:
        return "vazio (só zeros)"
    t = [((x[0] & 0x0F) << 16) | (x[1] << 8) | x[2] for x in filled]
    sync = sum(1 for x in filled if x[0] & 0x10)
    sec = sum(1 for x in filled if x[0] & 0x20)
    us = sorted(v * 2 / 3 for v in t)
    med = us[len(us) // 2]
    gaps = sum(1 for v in us if v > 2.5 * med)
    rpm = 60e6 / (med * 60) if med else 0
    return (f"{len(filled)} registros, sync {sync}, secundário {sec}, tempo mediano {med:.0f} us "
            f"(≈{rpm:.0f} rpm se 60 dentes), buracos (>2,5x) {gaps}, versão {buf[1023]}, 1os bytes {buf[:9].hex()}")


s = conectar()
f, sig = req(s, b"Q")
print("ECU:", sig.decode("latin1").strip("\x00 "), flush=True)
fh = open(OUT, "w")
fh.write("time_s;kind;rpm;status3;hex;note\n")
t0 = time.time()
rt = realtime(s)
print("tempo real: rpm %d MAP %.0f bateria %.1f status3 %d perdas %d" % (rt["rpm"], rt["map"], rt["bat"], rt["st3"], rt["sync"]), flush=True)
buf, err = logger(s)
fh.write("%.3f;arm;%d;%d;%s;%s\n" % (0, rt["rpm"], rt["st3"], buf.hex() if buf else "", err)); fh.flush()
print("ARM:", err or ("ok, " + resumo(buf)), flush=True)
last = time.time()
print(f"Pronto. Pode dar a partida (rodando {DUR:.0f} s). Arquivo: {OUT}", flush=True)
reads = 0
while time.time() - t0 < DUR:
    rt = realtime(s)
    now = time.time()
    ready = rt["st3"] & 2
    if (ready or now - last >= 1.2) and now - last >= 0.4:
        if rt["rpm"] == 0 and not ready and reads > 0 and now - last < 5:
            time.sleep(0.05); continue
        buf, err = logger(s)
        last = time.time(); reads += 1
        kind = "full" if ready else ("fired" if rt["rpm"] >= 450 else "partial")
        fh.write("%.3f;%s;%d;%d;%s;%s\n" % (now - t0, kind, rt["rpm"], rt["st3"], buf.hex() if buf else "", err)); fh.flush()
        print("t+%5.1f %-7s rpm %4d bat %.1f st3 %d perdas %d: %s" % (now - t0, kind, rt["rpm"], rt["bat"], rt["st3"], rt["sync"], err or resumo(buf)), flush=True)
    time.sleep(0.05)
s.close()
print("fim")
