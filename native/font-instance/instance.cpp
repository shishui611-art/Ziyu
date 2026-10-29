// SPDX-License-Identifier: GPL-3.0-only
// HarfBuzz is linked under its own permissive license (licenses/HarfBuzz-COPYING.txt).
#include "hb.h"
#include "hb-ot.h"
#include "hb-subset.h"
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include "glyph-cache.hh"
#include "cff-cache.hh"

static bool table(hb_face_t *face, hb_tag_t tag) {
  hb_blob_t *blob = hb_face_reference_table(face, tag);
  bool present = hb_blob_get_length(blob) != 0;
  hb_blob_destroy(blob);
  return present;
}

static bool close_value(int a, int b) { return std::abs(double(a) - double(b)) <= 1; }

static int subset_instance(int argc, char **argv) {
  if (argc < 6) return 64; // --subset source output face unicode-list [axis=value ...]
  char *end = nullptr;
  unsigned long index = std::strtoul(argv[4], &end, 10);
  if (!end || *end || end == argv[4] || index > 65535) return 64;
  auto blob = hb_blob_create_from_file_or_fail(argv[2]); if (!blob) return 65;
  auto face = hb_face_create(blob, unsigned(index));
  auto input = hb_subset_input_create_or_fail(); if (!input) return 66;
  hb_subset_input_keep_everything(input);
  hb_set_clear(hb_subset_input_glyph_set(input));
  auto unicodes = hb_subset_input_unicode_set(input); hb_set_clear(unicodes);
  FILE *points = std::fopen(argv[5], "rb"); if (!points) return 65;
  unsigned cp = 0, count = 0;
  while (std::fscanf(points, "%u", &cp) == 1) {
    if (cp > 0x10ffff || ++count > 0x110000) { std::fclose(points); return 67; }
    hb_set_add(unicodes, cp);
  }
  bool valid = std::feof(points) && count; std::fclose(points); if (!valid) return 67;
  std::vector<hb_variation_t> axes;
  if (argc > 6 && hb_ot_var_get_axis_count(face) != unsigned(argc - 6)) return 67;
  for (int i = 6; i < argc; ++i) {
    const char *equal = std::strchr(argv[i], '='); if (!equal || equal - argv[i] != 4) return 67;
    float value = std::strtof(equal + 1, &end);
    if (!end || *end || end == equal + 1 || !std::isfinite(value)) return 67;
    hb_tag_t tag = hb_tag_from_string(argv[i], 4);
    for (auto a : axes) if (a.tag == tag) return 67;
    if (!hb_subset_input_pin_axis_location(input, face, tag, value)) return 68;
    axes.push_back({tag, value});
  }
  hb_subset_input_set_flags(input, hb_subset_input_get_flags(input) | HB_SUBSET_FLAGS_RETAIN_GIDS);
  auto result = hb_subset_or_fail(face, input);
  if (!result || !hb_face_get_glyph_count(result) || hb_face_get_upem(result) != hb_face_get_upem(face)
      || (!axes.empty() && hb_ot_var_has_data(result))) return 69;
  auto original = hb_font_create(face), pinned = hb_font_create(result);
  hb_ot_font_set_funcs(original); hb_ot_font_set_funcs(pinned);
  hb_font_set_variations(original, axes.data(), unsigned(axes.size()));
  hb_codepoint_t code = HB_SET_VALUE_INVALID;
  while (hb_set_next(unicodes, &code)) {
    hb_codepoint_t a = 0, b = 0;
    if (!hb_font_get_nominal_glyph(original, code, &a) || !hb_font_get_nominal_glyph(pinned, code, &b)
        || a != b || !close_value(hb_font_get_glyph_h_advance(original, a), hb_font_get_glyph_h_advance(pinned, b))) return 70;
    hb_glyph_extents_t x{}, y{};
    bool has_x = hb_font_get_glyph_extents(original, a, &x), has_y = hb_font_get_glyph_extents(pinned, b, &y);
    if (has_x != has_y || (has_x && (!close_value(x.x_bearing, y.x_bearing) || !close_value(x.y_bearing, y.y_bearing)
        || !close_value(x.width, y.width) || !close_value(x.height, y.height)))) return 70;
  }
  auto output = hb_face_reference_blob(result); unsigned length = 0;
  const char *data = hb_blob_get_data(output, &length);
  FILE *stream = std::fopen(argv[3], "wb"); if (!stream) return 71;
  bool ok = length && std::fwrite(data, 1, length, stream) == length;
  if (std::fclose(stream)) ok = false;
  hb_blob_destroy(output); hb_font_destroy(original); hb_font_destroy(pinned);
  hb_face_destroy(result); hb_subset_input_destroy(input); hb_face_destroy(face); hb_blob_destroy(blob);
  return ok ? 0 : 72;
}

int main(int argc, char **argv) {
  if (argc > 1 && !std::strcmp(argv[1], "--cff-cache")) return ziyu_cff_cache::run(argc, argv);
  if (argc > 1 && !std::strcmp(argv[1], "--glyph-cache")) return ziyu_glyph_cache::run(argc, argv);
  if (argc > 1 && !std::strcmp(argv[1], "--subset")) return subset_instance(argc, argv);
  if (argc == 2 && !std::strcmp(argv[1], "--version")) {
    std::printf("ziyu-instance-v1 HarfBuzz %s\n", hb_version_string()); return 0;
  }
  if (argc < 5) return 64; // input output face tag=value ... (all axes)
  char *end = nullptr;
  unsigned long index = std::strtoul(argv[3], &end, 10);
  if (!end || *end || end == argv[3] || index > 65535) return 64;
  hb_blob_t *blob = hb_blob_create_from_file_or_fail(argv[1]);
  if (!blob) return 65;
  hb_face_t *face = hb_face_create(blob, static_cast<unsigned>(index));
  hb_subset_input_t *input = hb_subset_input_create_or_fail();
  if (!input) return 66;
  unsigned count = hb_face_get_glyph_count(face);
  if (!count || count > 65535 || hb_ot_var_get_axis_count(face) != unsigned(argc - 4)) return 67;
  hb_subset_input_keep_everything(input);
  hb_subset_input_set_flags(input, hb_subset_input_get_flags(input) | HB_SUBSET_FLAGS_RETAIN_GIDS);
  hb_variation_t *variations = static_cast<hb_variation_t *>(std::calloc(argc - 4, sizeof(hb_variation_t)));
  if (!variations) return 66;
  for (int i = 4; i < argc; ++i) {
    const char *equal = std::strchr(argv[i], '=');
    if (!equal || equal - argv[i] != 4) return 67;
    float value = std::strtof(equal + 1, &end);
    if (!end || *end || end == equal + 1 || !std::isfinite(value)) return 67;
    hb_tag_t tag = hb_tag_from_string(argv[i], 4);
    for (int j = 0; j < i - 4; ++j) if (variations[j].tag == tag) return 67;
    variations[i - 4] = {tag, value};
    if (!hb_subset_input_pin_axis_location(input, face, tag, value)) return 68;
  }
  hb_face_t *result = hb_subset_or_fail(face, input);
  // Malformed gvar can yield a nonempty subset with cmap/maxp but no outlines.
  const bool source_glyf = table(face, HB_TAG('g','l','y','f'));
  const bool source_cff2 = table(face, HB_TAG('C','F','F','2'));
  if (!result || hb_face_get_glyph_count(result) != count || hb_ot_var_get_axis_count(result)
      || !table(result, HB_TAG('h','e','a','d')) || hb_face_get_upem(face) != hb_face_get_upem(result)
      || (!source_glyf && !source_cff2)
      || (source_glyf && (!table(result, HB_TAG('g','l','y','f')) || !table(result, HB_TAG('l','o','c','a'))))
      || (source_cff2 && !table(result, HB_TAG('C','F','F','2')) && !table(result, HB_TAG('C','F','F',' ')))) return 69;
  hb_font_t *original = hb_font_create(face), *pinned = hb_font_create(result);
  hb_ot_font_set_funcs(original); hb_ot_font_set_funcs(pinned);
  hb_font_set_variations(original, variations, argc - 4);
  for (unsigned gid = 0; gid < count; ++gid) {
    if (!close_value(hb_font_get_glyph_h_advance(original, gid), hb_font_get_glyph_h_advance(pinned, gid))
        || !close_value(hb_font_get_glyph_v_advance(original, gid), hb_font_get_glyph_v_advance(pinned, gid))) return 70;
    hb_glyph_extents_t a{}, b{};
    bool has_a = hb_font_get_glyph_extents(original, gid, &a), has_b = hb_font_get_glyph_extents(pinned, gid, &b);
    if (has_a != has_b || (has_a && (!close_value(a.x_bearing, b.x_bearing)
        || !close_value(a.y_bearing, b.y_bearing) || !close_value(a.width, b.width) || !close_value(a.height, b.height)))) return 70;
  }
  hb_blob_t *output = hb_face_reference_blob(result);
  unsigned length = 0;
  const char *data = hb_blob_get_data(output, &length);
  FILE *file = std::fopen(argv[2], "wb");
  if (!file) return 71;
  bool ok = std::fwrite(data, 1, length, file) == length;
  if (std::fclose(file)) ok = false;
  hb_blob_destroy(output); hb_font_destroy(original); hb_font_destroy(pinned);
  hb_face_destroy(result); hb_subset_input_destroy(input); hb_face_destroy(face); hb_blob_destroy(blob);
  std::free(variations);
  return ok ? 0 : 72;
}
