#include "../app/src/main/jni/peerlink_backend.cpp"
#include <fstream>
#include <iostream>
int main(){
  BackendState s;
  const char* path="/tmp/peerlink_raw_smoke.pcapng";
  if(!start_raw_capture(&s,path)){ std::cerr<<"start failed\n"; return 1; }
  s.raw_capture_thread=std::thread(raw_capture_writer_loop,&s);
  uint8_t p1[40]{}; p1[0]=0x45; p1[2]=0; p1[3]=40;
  uint8_t p2[60]{}; p2[0]=0x60;
  enqueue_raw_capture(&s,true,p1,sizeof(p1),monotonic_ns());
  enqueue_raw_capture(&s,false,p2,sizeof(p2),monotonic_ns());
  request_raw_capture_flush(&s);
  auto st=raw_capture_stats(&s);
  if(st[0]!=2 || st[1]!=100 || st[2]||st[3]||st[4]) { std::cerr<<"bad stats\n"; return 2; }
  s.raw_capture_enabled=false; s.raw_capture_stop=true; s.raw_capture_cv.notify_all();
  s.raw_capture_thread.join();
  std::ifstream f(path,std::ios::binary|std::ios::ate);
  if(!f || f.tellg()<100){ std::cerr<<"small file\n"; return 3; }
  std::cout<<"PASS raw capture bytes="<<st[1]<<" file="<<f.tellg()<<"\n";
  return 0;
}
