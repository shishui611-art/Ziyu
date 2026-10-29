// SPDX-License-Identifier: GPL-3.0-only
// Width-free Type 2 bodies. The OEM publisher owns FD/private widths and tables.
#include <array>
#include <utility>

namespace ziyu_cff_cache {
using ziyu_glyph_cache::Bytes;
using ziyu_glyph_cache::Decoder;
using ziyu_glyph_cache::Outline;
using ziyu_glyph_cache::reference;
using ziyu_glyph_cache::u16;
using ziyu_glyph_cache::u32;
struct P { double x, y; };
struct Segment { P a, b, c, end; unsigned degree; };
struct Contour { P start; std::vector<Segment> segments; };
struct Path {
  std::vector<Contour> contours;
  size_t operations = 0;
  bool valid = true;
  bool float_input = false;
  double scale = 1;
  void check(P p) {
    if(!float_input)return;
    // hb_draw callbacks are float32. Do not guess at an integer-rounding
    // boundary where the original CFF 16.16 operand could round differently.
    for(double value:{p.x,p.y}) {
      float f=float(value);double ulp=std::abs(double(std::nextafter(f,std::numeric_limits<float>::infinity()))-value);
      double scaled=value*scale;
      if(!std::isfinite(value)||std::abs((scaled-std::floor(scaled))-.5)<=ulp*scale*2)valid=false;
    }
  }
  void move(P p) { check(p);if (++operations > 1000000) { valid=false; return; } contours.push_back({p, {}}); }
  P current() const { const auto &c=contours.back(); return c.segments.empty()?c.start:c.segments.back().end; }
  void segment(unsigned degree, P b, P c, P end) {
    check(end);if(degree>1)check(b);if(degree>2)check(c);
    if (contours.empty() || ++operations > 1000000) { valid=false; return; }
    auto a=current(); contours.back().segments.push_back({a,b,c,end,degree});
  }
};
static bool same(P a, P b) { return a.x==b.x && a.y==b.y; }
static bool from_glyf(Outline &o, double shift, Path &path) {
  unsigned begin=0;
  for (auto end:o.ends) {
    unsigned first=begin;
    while (first<=end && !o.points[first].on) ++first;
    if (first>end) return false; // Retain the established off-curve-only fallback.
    std::vector<ziyu_glyph_cache::Point> points;
    for (unsigned i=first; i<=end; ++i) points.push_back(o.points[i]);
    for (unsigned i=begin; i<first; ++i) points.push_back(o.points[i]);
    auto point=[&](unsigned i) { return P{points[i].x+shift,points[i].y}; };
    path.move(point(0));
    unsigned i=1;
    while (i<=points.size()) {
      unsigned j=i==points.size()?0:i;
      if (points[j].on) { path.segment(1,{}, {},point(j)); ++i; }
      else {
        P control=point(j); unsigned k=i+1==points.size()?0:i+1;
        if (points[k].on) { path.segment(2,control,{},point(k)); i+=2; }
        else { P q=point(k); path.segment(2,control,{}, {(control.x+q.x)/2,(control.y+q.y)/2}); ++i; }
      }
    }
    begin=end+1;
  }
  return path.valid;
}
static void move(hb_draw_funcs_t*,void *data,hb_draw_state_t*,float x,float y,void*) { static_cast<Path*>(data)->move({x,y}); }
static void line(hb_draw_funcs_t*,void *data,hb_draw_state_t*,float x,float y,void*) { static_cast<Path*>(data)->segment(1,{}, {},{x,y}); }
static void quad(hb_draw_funcs_t*,void *data,hb_draw_state_t*,float x,float y,float a,float b,void*) { static_cast<Path*>(data)->segment(2,{x,y},{},{a,b}); }
static void cubic(hb_draw_funcs_t*,void *data,hb_draw_state_t*,float x,float y,float a,float b,float c,float d,void*) { static_cast<Path*>(data)->segment(3,{x,y},{a,b},{c,d}); }
static void close(hb_draw_funcs_t*,void*,hb_draw_state_t*,void*) {}
struct Bounds {
  bool present=false; std::array<double,4> v{};
  void add(P p) {
    if (!present) { v={p.x,p.y,p.x,p.y}; present=true; }
    else { v[0]=std::min(v[0],p.x); v[1]=std::min(v[1],p.y); v[2]=std::max(v[2],p.x); v[3]=std::max(v[3],p.y); }
  }
  void curve(P a,P b,P c,P d) {
    add(a); add(d);
    for (unsigned axis=0;axis<2;++axis) {
      double p=axis?a.y:a.x, q=axis?b.y:b.x,r=axis?c.y:c.x,s=axis?d.y:d.x;
      double A=-p+3*q-3*r+s,B=2*(p-2*q+r),C=q-p;
      double roots[2]; unsigned n=0;
      if (std::abs(A)<1e-12) { if (std::abs(B)>1e-12) roots[n++]=-C/B; }
      else { double det=B*B-4*A*C; if (det>=0) { double v=std::sqrt(det);roots[n++]=(-B+v)/(2*A);roots[n++]=(-B-v)/(2*A); } }
      for(unsigned i=0;i<n;++i) { double t=roots[i],u=1-t; if(t>0 && t<1) add({u*u*u*a.x+3*u*u*t*b.x+3*u*t*t*c.x+t*t*t*d.x,u*u*u*a.y+3*u*u*t*b.y+3*u*t*t*c.y+t*t*t*d.y}); }
    }
  }
};
static bool integer(std::vector<unsigned char>& v, int n) {
  if(n>=-107 && n<=107) v.push_back(unsigned(n+139));
  else if(n>=108 && n<=1131) { unsigned x=n-108;v.push_back(247+x/256);v.push_back(x%256); }
  else if(n<=-108 && n>=-1131) { unsigned x=-n-108;v.push_back(251+x/256);v.push_back(x%256); }
  else if(n>=-32768 && n<=32767) { v.push_back(28);u16(v,n); }
  else return false;
  return true;
}
struct Writer {
  std::vector<unsigned char> bytes, pending;
  unsigned op=0,count=0;
  P previous{0,0}; double scale;
  bool valid=true;
  void flush() { if(!count)return;bytes.insert(bytes.end(),pending.begin(),pending.end());bytes.push_back(op);pending.clear();count=0; }
  void command(unsigned code,std::initializer_list<P> points) {
    std::array<int,6> delta{};unsigned n=0;
    for(auto p:points) {
      p={std::floor(p.x*scale+0.5),std::floor(p.y*scale+0.5)};
      if(!std::isfinite(p.x)||!std::isfinite(p.y)||std::abs(p.x)>32767||std::abs(p.y)>32767) { valid=false;return; }
      delta[n++]=int(p.x-previous.x);delta[n++]=int(p.y-previous.y);previous=p;
    }
    if(code==21||code==5) {
      if(!delta[0]) { code=code==21?4:7;delta[0]=delta[1];n=1; }
      else if(!delta[1]) { code=code==21?22:6;n=1; }
    } else if(code==8) {
      if(!delta[0]&&!delta[4]) { code=26;delta={delta[1],delta[2],delta[3],delta[5],0,0};n=4; }
      else if(!delta[1]&&!delta[5]) { code=27;delta={delta[0],delta[2],delta[3],delta[4],0,0};n=4; }
    }
    // h/vlineto operands alternate axes, so separate same-axis commands.
    bool single=code==4||code==22||code==21||code==6||code==7;
    if(code!=op||count+n>48||single)flush();
    op=code;
    for(unsigned i=0;i<n;++i)if(!integer(pending,delta[i])) { valid=false;return; }
    count+=n;
    if(single)flush();
  }
};
static bool encode(Path &path,bool reverse,double scale,std::vector<unsigned char>& data,Bounds &bounds) {
  Writer out;out.scale=scale;
  for(auto &contour:path.contours) {
    auto &segments=contour.segments;
    // Add the implicit closing edge before reversing the outline direction.
    if(!segments.empty()&&!same(segments.back().end,contour.start)) segments.push_back({segments.back().end,{},{},contour.start,1});
    if(reverse) {
      std::reverse(segments.begin(),segments.end());
      for(auto &s:segments) { std::swap(s.a,s.end); if(s.degree==3)std::swap(s.b,s.c); }
    }
    out.command(21,{contour.start});
    if(segments.empty()) bounds.add(contour.start);
    for(size_t i=0;i<segments.size();++i) {
      auto s=segments[i];
      if(s.degree==1) {
        bounds.add(s.a);bounds.add(s.end);
        if(i+1<segments.size()||!same(s.end,contour.start)||same(s.a,s.end)) out.command(5,{s.end});
      } else {
        P b=s.b,c=s.c;
        if(s.degree==2) { b={s.a.x+(2.0/3)*(s.b.x-s.a.x),s.a.y+(2.0/3)*(s.b.y-s.a.y)};c={s.end.x+(2.0/3)*(s.b.x-s.end.x),s.end.y+(2.0/3)*(s.b.y-s.end.y)}; }
        bounds.curve(s.a,b,c,s.end);out.command(8,{b,c,s.end});
      }
      if(!out.valid)return false;
    }
  }
  out.flush();data=std::move(out.bytes);
  return out.valid&&path.valid;
}
static void f64(std::vector<unsigned char>& bytes,double value) {
  uint64_t bits;std::memcpy(&bits,&value,8);u32(bytes,uint32_t(bits>>32));u32(bytes,uint32_t(bits));
}
static int run(int argc,char **argv) {
  if(argc!=6)return 64;
  char *end=nullptr;auto index=std::strtoul(argv[4],&end,10);
  if(!end||*end||end==argv[4]||index>65535)return 64;
  auto upem=std::strtoul(argv[5],&end,10);
  if(!end||*end||end==argv[5]||upem<16||upem>16384)return 64;
  auto blob=hb_blob_create_from_file_or_fail(argv[2]);if(!blob)return 65;
  auto face=hb_face_create(blob,unsigned(index));
  unsigned n=hb_face_get_glyph_count(face),original=hb_face_get_upem(face);
  std::vector<hb_blob_t*> owned;
  Bytes head=reference(face,HB_TAG('h','e','a','d'),owned),hhea=reference(face,HB_TAG('h','h','e','a'),owned),
        hmtx=reference(face,HB_TAG('h','m','t','x'),owned),loca=reference(face,HB_TAG('l','o','c','a'),owned),glyf=reference(face,HB_TAG('g','l','y','f'),owned);
  bool is_glyf=glyf.n!=0;
  unsigned metrics=hhea.has(0,36)?hhea.u16(34):0;int format=head.has(0,54)?head.s16(50):-1;
  bool valid=n&&n<=65535&&original>=16&&original<=16384&&!hb_ot_var_has_data(face);
  if(is_glyf)valid=valid&&metrics&&metrics<=n&&(format==0||format==1)&&loca.has(0,(n+1)*(format?4:2))&&hmtx.has(0,metrics*4+(n-metrics)*2);
  else { auto cff=reference(face,HB_TAG('C','F','F',' '),owned),cff2=reference(face,HB_TAG('C','F','F','2'),owned);valid=valid&&(cff.n||cff2.n); }
  auto font=hb_font_create(face);hb_ot_font_set_funcs(font);hb_font_set_scale(font,original,original);
  auto funcs=hb_draw_funcs_create();hb_draw_funcs_set_move_to_func(funcs,move,nullptr,nullptr);
  hb_draw_funcs_set_line_to_func(funcs,line,nullptr,nullptr);hb_draw_funcs_set_quadratic_to_func(funcs,quad,nullptr,nullptr);
  hb_draw_funcs_set_cubic_to_func(funcs,cubic,nullptr,nullptr);hb_draw_funcs_set_close_path_func(funcs,close,nullptr,nullptr);
  int result=67;
  if(valid) {
    Decoder decoder{glyf,loca,hmtx,n,metrics,format==1,std::vector<unsigned char>(n)};
    std::vector<unsigned char> statuses(n),bounds_bytes,data,header;std::vector<uint32_t> offsets{0};
    for(unsigned gid=0;gid<n;++gid) {
      Path path;path.float_input=!is_glyf;path.scale=double(upem)/original;bool ok=true;
      if(is_glyf) { Outline o;ok=decoder.decode(gid,o);if(ok) { auto at=decoder.offset(gid);double shift=o.points.empty()||glyf.s16(at)<0?0:decoder.bearing(gid)-glyf.s16(at+2);ok=from_glyf(o,shift,path); } }
      else { hb_font_draw_glyph(font,gid,funcs,&path);hb_glyph_extents_t e{};if(path.contours.empty()&&hb_font_get_glyph_extents(font,gid,&e)&&(e.width||e.height))ok=false; }
      Bounds bounds;std::vector<unsigned char> body;
      if(ok&&encode(path,is_glyf,double(upem)/original,body,bounds)) {
        statuses[gid]=bounds.present?2:1;
        if(data.size()+body.size()>64*1024*1024) { valid=false;break; }
        data.insert(data.end(),body.begin(),body.end());
      }
      for(unsigned axis=0;axis<4;++axis)f64(bounds_bytes,statuses[gid]==2?bounds.v[axis]:0);
      offsets.push_back(uint32_t(data.size()));
    }
    if(valid) {
      const char magic[]="ZIYUCF01";header.insert(header.end(),magic,magic+8);u32(header,n);u16(header,original);u16(header,unsigned(upem));u32(header,unsigned(data.size()));
      header.insert(header.end(),statuses.begin(),statuses.end());header.insert(header.end(),bounds_bytes.begin(),bounds_bytes.end());for(auto at:offsets)u32(header,at);
      FILE *out=std::fopen(argv[3],"wb");if(out) { bool ok=std::fwrite(header.data(),1,header.size(),out)==header.size()&&std::fwrite(data.data(),1,data.size(),out)==data.size();if(std::fclose(out))ok=false;result=ok?0:72; }else result=71;
    }
  }
  hb_draw_funcs_destroy(funcs);hb_font_destroy(font);for(auto b:owned)hb_blob_destroy(b);hb_face_destroy(face);hb_blob_destroy(blob);return result;
}
} // namespace ziyu_cff_cache
