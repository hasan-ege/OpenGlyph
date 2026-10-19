# Projects an equirectangular lunar map (lon -180..180 left to right) onto the near-side
# disc as seen from Earth (orthographic, north up, east on the right), N x N, 0..255.
import sys, math, base64
from PIL import Image
src, n = sys.argv[1], int(sys.argv[2])
im = Image.open(src).convert('L')
W, H = im.size
px = im.load()
def sample(u, v):  # bilinear, u wraps
    u %= W; v = min(max(v, 0), H - 1.001)
    x0, y0 = int(u), int(v); fx, fy = u - x0, v - y0
    x1 = (x0 + 1) % W; y1 = min(y0 + 1, H - 1)
    a = px[x0, y0] * (1 - fx) + px[x1, y0] * fx
    b = px[x0, y1] * (1 - fx) + px[x1, y1] * fx
    return a * (1 - fy) + b * fy
vals = []
for j in range(n):
    for i in range(n):
        # average 4x4 sub-samples per cell
        acc, cnt = 0.0, 0
        for sj in range(4):
            for si in range(4):
                nx = (i + (si + 0.5) / 4) / n * 2 - 1
                ny = (j + (sj + 0.5) / 4) / n * 2 - 1
                rr = nx * nx + ny * ny
                if rr > 1: continue
                z = math.sqrt(1 - rr)
                lat = math.degrees(math.asin(-ny)); lon = math.degrees(math.atan2(nx, z))
                acc += sample((lon + 180) / 360 * W, (90 - lat) / 180 * H); cnt += 1
        vals.append(acc / cnt if cnt else 0.0)
inside = sorted(v for v in vals if v > 0)
hi = inside[int(len(inside) * 0.99)]
out = bytes(min(255, int(round(v / hi * 255))) for v in vals)
print(base64.b64encode(out).decode())
