// SPDX-License-Identifier: GPL-3.0-only
// Batch-decompose static TrueType outlines. The Python publisher retains all
// OEM mapping, dependency, variation, metric, output and rollback contracts.
#include <algorithm>
#include <cstdint>
#include <limits>
#include <vector>

namespace ziyu_glyph_cache {
struct Bytes {
  const unsigned char *p; size_t n;
  bool has(size_t at, size_t count) const { return at <= n && count <= n - at; }
  unsigned u16(size_t at) const { return unsigned(p[at]) * 256 + p[at + 1]; }
  int s16(size_t at) const { unsigned v = u16(at); return v >= 32768 ? int(v) - 65536 : int(v); }
  uint32_t u32(size_t at) const { return uint32_t(u16(at)) * 65536 + u16(at + 2); }
};
struct Point { double x, y; unsigned char on; };
struct Outline { std::vector<Point> points; std::vector<unsigned> ends; };
struct Decoder {
  Bytes glyf, loca, hmtx;
  unsigned count, metrics; bool long_loca;
  std::vector<unsigned char> visiting;
  uint32_t offset(unsigned gid) const { return long_loca ? loca.u32(gid * 4) : loca.u16(gid * 2) * 2; }
  int bearing(unsigned gid) const { return hmtx.s16(gid < metrics ? gid * 4 + 2 : metrics * 4 + (gid - metrics) * 2); }
  bool decode(unsigned gid, Outline &out, unsigned depth = 0) {
    if (gid >= count || depth > 32 || visiting[gid]) return false;
    uint32_t begin = offset(gid), end = offset(gid + 1);
    if (end < begin || !glyf.has(begin, end - begin)) return false;
    if (end == begin) return true;
    Bytes g{glyf.p + begin, end - begin};
    if (!g.has(0, 10)) return false;
    int contours = g.s16(0);
    if (!contours) return true;
    visiting[gid] = 1;
    bool valid = contours > 0 ? simple(g, unsigned(contours), out) : composite(g, out, depth);
    visiting[gid] = 0;
    return valid;
  }
  bool simple(Bytes g, unsigned contours, Outline &out) {
    if (!g.has(10, contours * 2 + 2)) return false;
    unsigned n = g.u16(10 + (contours - 1) * 2) + 1;
    if (n > 65535) return false;
    for (unsigned i = 0; i < contours; ++i) {
      unsigned e = g.u16(10 + i * 2);
      if (e >= n || (i && e <= out.ends.back())) return false;
      out.ends.push_back(e);
    }
    size_t at = 10 + contours * 2;
    unsigned instructions = g.u16(at); at += 2;
    if (!g.has(at, instructions)) return false;
    at += instructions;
    std::vector<unsigned char> flags;
    while (flags.size() < n) {
      if (!g.has(at, 1)) return false;
      unsigned char f = g.p[at++];
      if (f & 128) return false; // Cubic TrueType stays on the established path.
      unsigned repeat = 0;
      if (f & 8) { if (!g.has(at, 1)) return false; repeat = g.p[at++]; }
      if (repeat + 1 > n - flags.size()) return false;
      flags.insert(flags.end(), repeat + 1, f);
    }
    out.points.resize(n);
    for (unsigned axis = 0; axis < 2; ++axis) {
      int coordinate = 0;
      unsigned small = axis ? 4 : 2, same = axis ? 32 : 16;
      for (unsigned i = 0; i < n; ++i) {
        int delta = 0;
        if (flags[i] & small) {
          if (!g.has(at, 1)) return false;
          delta = g.p[at++]; if (!(flags[i] & same)) delta = -delta;
        } else if (!(flags[i] & same)) {
          if (!g.has(at, 2)) return false; delta = g.s16(at); at += 2;
        }
        coordinate += delta;
        if (axis) out.points[i].y = coordinate; else out.points[i].x = coordinate;
        out.points[i].on = flags[i] & 1;
      }
    }
    return true;
  }
  bool composite(Bytes g, Outline &out, unsigned depth) {
    size_t at = 10; unsigned flags = 0, components = 0;
    do {
      if (!g.has(at, 4) || ++components > 1024) return false;
      flags = g.u16(at); unsigned gid = g.u16(at + 2); at += 4;
      if (!(flags & 2)) return false; // Point-matched components use Python.
      bool words = flags & 1;
      if (!g.has(at, words ? 4 : 2)) return false;
      double dx = words ? g.s16(at) : int(int8_t(g.p[at]));
      double dy = words ? g.s16(at + 2) : int(int8_t(g.p[at + 1]));
      at += words ? 4 : 2;
      double xx = 1, xy = 0, yx = 0, yy = 1;
      unsigned matrix = flags & (8 | 64 | 128);
      if (matrix && matrix != 8 && matrix != 64 && matrix != 128) return false;
      unsigned bytes = matrix == 128 ? 8 : matrix == 64 ? 4 : matrix == 8 ? 2 : 0;
      if (!g.has(at, bytes)) return false;
      if (matrix == 8) xx = yy = g.s16(at) / 16384.0;
      if (matrix == 64) { xx = g.s16(at) / 16384.0; yy = g.s16(at + 2) / 16384.0; }
      if (matrix == 128) { xx = g.s16(at) / 16384.0; xy = g.s16(at + 2) / 16384.0;
                           yx = g.s16(at + 4) / 16384.0; yy = g.s16(at + 6) / 16384.0; }
      at += bytes;
      if ((flags & 2048) && (flags & 4096)) return false;
      if (flags & 2048) { double x = dx; dx = x * xx + dy * yx; dy = x * xy + dy * yy; }
      Outline part;
      if (!decode(gid, part, depth + 1) || part.points.size() > 65535 - out.points.size()) return false;
      unsigned start = unsigned(out.points.size());
      for (auto p : part.points) out.points.push_back({p.x * xx + p.y * yx + dx, p.x * xy + p.y * yy + dy, p.on});
      for (auto e : part.ends) out.ends.push_back(start + e);
      if (out.ends.size() > 32767) return false;
    } while (flags & 32);
    return true;
  }
};
static void u16(std::vector<unsigned char> &v, int n) { v.push_back((unsigned(n) >> 8) & 255); v.push_back(unsigned(n) & 255); }
static void u32(std::vector<unsigned char> &v, uint32_t n) { u16(v, int(n >> 16)); u16(v, int(n & 65535)); }
static bool encode(Outline &o, double scale, double shift, std::vector<unsigned char> &v) {
  Outline clean; unsigned start = 0;
  for (auto end : o.ends) {
    // The pen path starts at the first on-curve point, not necessarily the
    // first stored point. Rotate before removing a duplicate closing point;
    // otherwise a coincident off-curve control point can be lost.
    std::vector<Point> contour;
    unsigned first = start;
    for (unsigned i = start; i <= end; ++i) if (o.points[i].on) { first = i; break; }
    for (unsigned i = first; i <= end; ++i) contour.push_back(o.points[i]);
    for (unsigned i = start; i < first; ++i) contour.push_back(o.points[i]);
    if (contour.size() > 1 && (contour.back().on || !contour.front().on)
        && contour.front().x == contour.back().x && contour.front().y == contour.back().y)
      contour.pop_back();
    if (end > start) {
      clean.points.insert(clean.points.end(), contour.begin(), contour.end());
      clean.ends.push_back(unsigned(clean.points.size()) - 1);
    }
    start = end + 1;
  }
  if (clean.points.empty()) return true;
  int xmin = 32767, ymin = 32767, xmax = -32768, ymax = -32768;
  for (auto &p : clean.points) {
    p.x = std::floor((p.x + shift) * scale + 0.5); p.y = std::floor(p.y * scale + 0.5);
    if (!std::isfinite(p.x) || !std::isfinite(p.y) || p.x < -32768 || p.x > 32767 || p.y < -32768 || p.y > 32767) return false;
    xmin = std::min(xmin, int(p.x)); xmax = std::max(xmax, int(p.x));
    ymin = std::min(ymin, int(p.y)); ymax = std::max(ymax, int(p.y));
  }
  u16(v, int(clean.ends.size())); u16(v, xmin); u16(v, ymin); u16(v, xmax); u16(v, ymax);
  for (auto end : clean.ends) u16(v, int(end));
  u16(v, 0); // Both the existing decomposition and this cache discard source instructions.
  std::vector<unsigned char> flags, xs, ys;
  int oldx = 0, oldy = 0;
  for (auto p : clean.points) {
    unsigned char flag = p.on;
    int dx = int(p.x) - oldx, dy = int(p.y) - oldy; oldx = int(p.x); oldy = int(p.y);
    if (!dx) flag |= 16;
    else if (std::abs(dx) <= 255) { flag |= 2; if (dx > 0) flag |= 16; xs.push_back(unsigned(std::abs(dx))); }
    else { if (dx < -32768 || dx > 32767) return false; u16(xs, dx); }
    if (!dy) flag |= 32;
    else if (std::abs(dy) <= 255) { flag |= 4; if (dy > 0) flag |= 32; ys.push_back(unsigned(std::abs(dy))); }
    else { if (dy < -32768 || dy > 32767) return false; u16(ys, dy); }
    flags.push_back(flag);
  }
  for (size_t i = 0; i < flags.size();) {
    size_t end = i + 1; while (end < flags.size() && end - i < 256 && flags[end] == flags[i]) ++end;
    v.push_back(flags[i] | (end - i > 1 ? 8 : 0)); if (end - i > 1) v.push_back(unsigned(end - i - 1)); i = end;
  }
  v.insert(v.end(), xs.begin(), xs.end()); v.insert(v.end(), ys.begin(), ys.end());
  return true;
}
static Bytes reference(hb_face_t *face, hb_tag_t tag, std::vector<hb_blob_t *> &owned) {
  auto blob = hb_face_reference_table(face, tag); owned.push_back(blob);
  unsigned n = 0; const char *p = hb_blob_get_data(blob, &n); return {reinterpret_cast<const unsigned char *>(p), n};
}
static int run(int argc, char **argv) {
  if (argc != 6) return 64; // --glyph-cache source output face target-upem
  char *end = nullptr; unsigned long face_index = std::strtoul(argv[4], &end, 10);
  if (!end || *end || end == argv[4] || face_index > 65535) return 64;
  unsigned long upem = std::strtoul(argv[5], &end, 10);
  if (!end || *end || end == argv[5] || upem < 16 || upem > 16384) return 64;
  auto blob = hb_blob_create_from_file_or_fail(argv[2]); if (!blob) return 65;
  auto face = hb_face_create(blob, unsigned(face_index));
  std::vector<hb_blob_t *> owned;
  Bytes head = reference(face, HB_TAG('h','e','a','d'), owned),
        hhea = reference(face, HB_TAG('h','h','e','a'), owned),
        hmtx = reference(face, HB_TAG('h','m','t','x'), owned),
        loca = reference(face, HB_TAG('l','o','c','a'), owned),
        glyf = reference(face, HB_TAG('g','l','y','f'), owned);
  unsigned n = hb_face_get_glyph_count(face), original_upem = hb_face_get_upem(face);
  bool valid = n && n <= 65535 && head.has(0, 54) && hhea.has(0, 36)
               && original_upem >= 16 && original_upem <= 16384 && !hb_ot_var_has_data(face);
  unsigned metrics = valid ? hhea.u16(34) : 0;
  int format = valid ? head.s16(50) : -1;
  valid = valid && metrics && metrics <= n && (format == 0 || format == 1)
          && loca.has(0, size_t(n + 1) * (format ? 4 : 2))
          && hmtx.has(0, metrics * 4 + (n - metrics) * 2) && glyf.n;
  int result = 67;
  if (valid) {
    Decoder d{glyf, loca, hmtx, n, metrics, format == 1, std::vector<unsigned char>(n)};
    std::vector<unsigned char> statuses(n), data, header;
    std::vector<uint32_t> offsets{0};
    for (unsigned gid = 0; gid < n; ++gid) {
      Outline o; std::vector<unsigned char> encoded;
      uint32_t at = d.offset(gid);
      if (d.decode(gid, o)) {
        // FontTools applies the top-level LSB offset to simple outlines only;
        // composite Glyph.draw forwards component transforms without that offset.
        double shift = o.points.empty() || glyf.s16(at) < 0 ? 0 : d.bearing(gid) - glyf.s16(at + 2);
        if (encode(o, double(upem) / original_upem, shift, encoded)) {
          statuses[gid] = encoded.empty() ? 1 : 2;
          if (data.size() + encoded.size() > 64 * 1024 * 1024) { valid = false; break; }
          data.insert(data.end(), encoded.begin(), encoded.end());
        }
      }
      offsets.push_back(uint32_t(data.size()));
    }
    if (valid) {
      const char magic[] = "ZIYUGC01"; header.insert(header.end(), magic, magic + 8);
      u32(header, n); u16(header, original_upem); u16(header, unsigned(upem)); u32(header, uint32_t(data.size()));
      header.insert(header.end(), statuses.begin(), statuses.end()); for (auto at : offsets) u32(header, at);
      FILE *output = std::fopen(argv[3], "wb");
      if (output) {
        bool ok = std::fwrite(header.data(), 1, header.size(), output) == header.size()
                  && std::fwrite(data.data(), 1, data.size(), output) == data.size();
        if (std::fclose(output)) ok = false; result = ok ? 0 : 72;
      } else result = 71;
    }
  }
  for (auto b : owned) hb_blob_destroy(b);
  hb_face_destroy(face); hb_blob_destroy(blob); return result;
}
} // namespace ziyu_glyph_cache
