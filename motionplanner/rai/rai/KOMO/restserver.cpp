#include "restserver.h"

Restserver::Restserver(OptConstrained* _opt) : server(), mutex() {
  opt = _opt;
  server.Get("/helloworld", [this](const httplib::Request& req, httplib::Response& res){hello_world(req,res);});
  server.Get("/sample", [this](const httplib::Request& req, httplib::Response& res){sample(req,res);});
  server.Post("/sample", [this](const httplib::Request& req, httplib::Response& res){sample(req,res);});
  server.Get("/hessian", [this](const httplib::Request& req, httplib::Response& res){hessian(req,res);});
  server.Post("/hessian", [this](const httplib::Request& req, httplib::Response& res){hessian(req,res);});
  server.Get("/numdims", [this](const httplib::Request& req, httplib::Response& res){numdims(req,res);});
  server.Get("/lasteval", [this](const httplib::Request& req, httplib::Response& res){lasteval(req,res);});
}

void Restserver::hello_world(const httplib::Request& req, httplib::Response& res) {
  std::stringstream out;
  out << "Hello World!" << std::endl;
  if(req.has_param("q")){
    out << "q= " << req.get_param_value("q") << std::endl;
  }
  res.set_content(out.str(), "text/plain");
}

void readVector(const char* str, std::vector<double>& vec) {
  std::stringstream stream(str);
  double val=0.0;
  while(stream >> val)
    vec.push_back(val);
  vec.shrink_to_fit();
}

void readVectorBinary(const char* str, std::vector<double>& vec, size_t numelems) {
  size_t j = 0;
  char chars[8];
  char chars_[8];
  for(size_t i=0; i<numelems/8; i++){
    chars_[7]=chars[0] = str[j++];
    chars_[6]=chars[1] = str[j++];
    chars_[5]=chars[2] = str[j++];
    chars_[4]=chars[3] = str[j++];
    chars_[3]=chars[4] = str[j++];
    chars_[2]=chars[5] = str[j++];
    chars_[1]=chars[6] = str[j++];
    chars_[0]=chars[7] = str[j++];
    const double* v = reinterpret_cast<const double*>(chars);
    const double* v_ = reinterpret_cast<const double*>(chars_);
    //vec.push_back(values[i]);
    vec.push_back(v_[0]);
  }
  vec.shrink_to_fit();
}

void Restserver::sample(const httplib::Request& req, httplib::Response& res) {
  std::clock_t req_start = std::clock();
  std::stringstream out;
  bool asText = req.has_param("format") && req.get_param_value("format")=="text";
  if(
     !req.has_param("fn") || !req.has_param("n0") || !req.has_param("n1") || !req.has_param("d0") || !req.has_param("d1")
     || (asText && (!req.has_header("origin") || !req.has_header("v0") || !req.has_header("v1")))
     )
  {
    out << "missing some params.";
    out << std::endl;
    out << "fn=lagrangian,features";
    out << std::endl;
    out << "n0=(integer) number of steps in v0 direction";
    out << std::endl;
    out << "n1=(integer) number of steps in v1 direction";
    out << std::endl;
    out << "d0=(float) step size in v0 direction";
    out << std::endl;
    out << "d1=(float) stepsize in v1 direction";
    out << std::endl;
    out << "header needs to contain vectors origin, v0 and v1";
    out << std::endl;
  } else {
    std::string fn = req.get_param_value("fn");
    size_t n0 = 0; std::sscanf(req.get_param_value("n0").c_str(), "%zu", &n0);
    size_t n1 = 0; std::sscanf(req.get_param_value("n1").c_str(), "%zu", &n1);
    double d0 = 0.0; std::sscanf(req.get_param_value("d0").c_str(), "%lf", &d0);
    double d1 = 0.0; std::sscanf(req.get_param_value("d1").c_str(), "%lf", &d1);

    double mu = std::nan(""); if(req.has_param("mu")) std::sscanf(req.get_param_value("mu").c_str(), "%lf", &mu);
    double nu = std::nan(""); if(req.has_param("nu")) std::sscanf(req.get_param_value("nu").c_str(), "%lf", &nu);

    std::vector<double> origin_;
    std::vector<double> v0_;
    std::vector<double> v1_;
    std::vector<double> lambda_;
    if(asText){
      readVector(req.get_header_value("origin").c_str(), origin_);
      readVector(req.get_header_value("v0").c_str(), v0_);
      readVector(req.get_header_value("v1").c_str(), v1_);
      readVector(req.get_header_value("lambda").c_str(), lambda_);
    } else {
      std::vector<double> all;
      readVectorBinary(req.body.data(), all, req.body.size());
      size_t numelems = opt->L.x.N;
      int j=0;
      for(size_t i=0; i<numelems; i++){
        origin_.push_back(all[numelems*0+i]);
        v0_.push_back(    all[numelems*1+i]);
        v1_.push_back(    all[numelems*2+i]);
        j+=3;
      }
      for(size_t i=j; i<all.size(); i++){
        lambda_.push_back(all[i]);
      }
    }

    arr origin(origin_);
    arr v0(v0_);
    arr v1(v1_);
    arr lambda(lambda_);

    if(fn == "lagrangian"){
      arr samples;
      std::clock_t time_start = std::clock();
      sample_lagrangian(samples, origin, v0, v1, d0, d1, n0, n1);
      std::clock_t time_end = std::clock();
      std::cout << "time to sample:" << (1000.0 * (time_end-time_start)) / CLOCKS_PER_SEC << " ms" << std::endl;
      if(asText){
        for(double v: samples)
          out << v << " ";
      } else {
        for(double v: samples){
          // sending double values as 8 bytes (chars)
          char* chars = reinterpret_cast<char*>(&v);
          for(size_t i=8; i>0; i--)
            out << chars[i-1];
        }
      }
    }

    if(fn == "all"){
      std::vector<std::vector<double>> samples(opt->L.phi_x.size()+1);
      std::clock_t time_start = std::clock();
      sample_all(samples, origin, v0, v1, d0, d1, n0, n1, mu,nu,lambda);
      std::clock_t time_end = std::clock();
      std::cout << "time to sample:" << (1000.0 * (time_end-time_start)) / CLOCKS_PER_SEC << " ms" << std::endl;
      if(asText){
        for(std::vector<double>& phisamples : samples){
          for(double v: phisamples){
            out << v << " ";
          }
          out << std::endl;
        }
      } else {
        for(std::vector<double>& phisamples : samples){
          for(double v: phisamples){
            // sending double values as 8 bytes (chars)
            char* chars = reinterpret_cast<char*>(&v);
            for(size_t i=8; i>0; i--){
              out << chars[i-1];
            }
          }
        }
      }
    }
  }
  res.set_content(out.str(), "text/plain");
  std::clock_t req_end = std::clock();
  std::cout << "time to answer:" << (1000.0 * (req_end-req_start)) / CLOCKS_PER_SEC << " ms" << std::endl;
}

void Restserver::hessian(const httplib::Request& req, httplib::Response& res) {
  std::clock_t req_start = std::clock();
  std::stringstream out;
  bool asText = req.has_param("format") && req.get_param_value("format")=="text";
  if(asText && (!req.has_header("origin") || !req.has_header("v0") || !req.has_header("v1")))
  {
    out << "missing some params.";
    out << std::endl;
    out << "header needs to contain vectors origin, v0 and v1";
    out << std::endl;
  } else {
    double mu = std::nan(""); if(req.has_param("mu")) std::sscanf(req.get_param_value("mu").c_str(), "%lf", &mu);
    double nu = std::nan(""); if(req.has_param("nu")) std::sscanf(req.get_param_value("nu").c_str(), "%lf", &nu);

    std::vector<double> origin_;
    std::vector<double> lambda_;
    if(asText){
      readVector(req.get_header_value("origin").c_str(), origin_);
      readVector(req.get_header_value("lambda").c_str(), lambda_);
    } else {
      std::vector<double> all;
      readVectorBinary(req.body.data(), all, req.body.size());
      size_t numelems = opt->L.x.N;
      int j=0;
      for(size_t i=0; i<numelems; i++){
        origin_.push_back(all[i]);
        j+=1;
      }
      for(size_t i=j; i<all.size(); i++){
        lambda_.push_back(all[i]);
      }
    }

    arr origin(origin_);
    arr lambda(lambda_);

    std::vector<double> samples;
    std::clock_t time_start = std::clock();
    hessian_at(samples, origin, mu,nu,lambda);
    std::clock_t time_end = std::clock();
    std::cout << "time to hessian:" << (1000.0 * (time_end-time_start)) / CLOCKS_PER_SEC << " ms" << std::endl;
    if(asText){
      for(double v: samples)
        out << v << " ";
    } else {
      for(double v: samples){
        // sending double values as 8 bytes (chars)
        char* chars = reinterpret_cast<char*>(&v);
        for(size_t i=8; i>0; i--)
          out << chars[i-1];
      }
    }
  }
  res.set_content(out.str(), "text/plain");
  std::clock_t req_end = std::clock();
  std::cout << "time to answer:" << (1000.0 * (req_end-req_start)) / CLOCKS_PER_SEC << " ms" << std::endl;
}

void Restserver::numdims(const httplib::Request &req, httplib::Response &res)
{
  std::stringstream out;
  out << opt->L.x.size();
  res.set_content(out.str(), "text/plain");
}

void Restserver::lasteval(const httplib::Request &req, httplib::Response &res)
{
  std::stringstream out;
  for(double val : opt->L.x)
    out << val << " ";
  out << std::endl;
  res.set_content(out.str(), "text/plain");
}

void Restserver::sample_lagrangian(std::vector<double>& samples, arr& origin, arr& v0, arr& v1, double d0, double d1, size_t n0, size_t n1) {
  // disable logging
  bool logging = opt->L.enableLogging;
  opt->L.enableLogging = false;

  arr x;
  arr x_;
  for(size_t i=0; i<n1; i++){
    x_ = origin + (i*d1)*v1;
    for(size_t j=0; j<n0; j++){
      x = x_ + (j*d0)*v0;
      double v = opt->L.lagrangian(NoArr,NoArr, x);
      samples.push_back(v);
    }
  }
  samples.shrink_to_fit();

  // enable logging
  opt->L.enableLogging = logging;
}

void Restserver::sample_all(std::vector<std::vector<double>> &samples, arr &origin, arr &v0, arr &v1, double d0, double d1, size_t n0, size_t n1, double mu, double nu, arr &lambda)
{
  mutex.lock();
  // disable logging
  bool logging = opt->L.enableLogging;
  opt->L.enableLogging = false;

  // set dual variables accordingly
  double mu_ = opt->L.mu;
  double nu_ = opt->L.nu;
  arr& lambda_ = opt->L.lambda;
  if(!std::isnan(mu) && !std::isnan(nu)){
    opt->L.mu=mu;
    opt->L.nu=nu;
    opt->L.lambda=lambda;
  }

  arr x;
  arr x_;

  for(size_t i=0; i<n1; i++){
    x_ = origin + (i*d1)*v1;
    for(size_t j=0; j<n0; j++){
      x = x_ + (j*d0)*v0;

      double v = opt->L.lagrangian(NoArr,NoArr, x);
      arr& phi = opt->L.phi_x;

      samples.at(0).push_back(v);
      for(size_t k=0; k<phi.size(); k++){
        std::vector<double>& s = samples.at(k+1);
        s.push_back(phi.at(k));
      }

    }
  }

  // reset duals to backup
  opt->L.mu=mu_;
  opt->L.nu=nu_;
  opt->L.lambda=lambda_;

  // enable logging
  opt->L.enableLogging = logging;
  mutex.unlock();
}

double getValueFromSparse(rai::SparseMatrix& m, uint i, uint j){
  // copy of sparsematrix::elem(i,j) but returns 0 instead of creating new 0 element
  if(m.rows.N){
    uintA& r = m.rows(i);
    uintA& c = m.cols(j);
    if(r.N < c.N){
      for(uint rj=0;rj<r.d0;rj++) if(r(rj,0)==j) return m.Z.elem(r(rj,1));
    }else{
      for(uint ci=0;ci<c.d0;ci++) if(c(ci,0)==i) return m.Z.elem(c(ci,1));
    }
  }else{
    for(uint k=0;k<m.elems.d0;k++)
      if(m.elems.p[2*k]==(int)i && m.elems.p[2*k+1]==(int)j) return m.Z.elem(k);
  }
  return 0.0;
}

void Restserver::hessian_at(std::vector<double> &hess, arr &origin, double mu, double nu, arr &lambda)
{
  mutex.lock();
  // disable logging
  bool logging = opt->L.enableLogging;
  opt->L.enableLogging = false;

  // set dual variables accordingly
  double mu_ = opt->L.mu;
  double nu_ = opt->L.nu;
  arr& lambda_ = opt->L.lambda;
  if(!std::isnan(mu) && !std::isnan(nu)){
    opt->L.mu=mu;
    opt->L.nu=nu;
    opt->L.lambda=lambda;
  }
  arr hessian;
  double v = opt->L.lagrangian(NoArr, hessian, origin);
  if(isSparseMatrix(hessian)){
    rai::SparseMatrix sparseHessian = hessian.sparse();
    for(size_t i=0; i<hessian.d0; i++){
      for(size_t j=0; j<hessian.d1; j++){
        double v = getValueFromSparse(sparseHessian,i,j);
        hess.push_back(v);
      }
    }
  } else {
    for(size_t i=0; i<hessian.d0; i++){
      for(size_t j=0; j<hessian.d1; j++){
        double v = hessian.elem(i,j);
        hess.push_back(v);
      }
    }
  }

  // reset duals to backup
  opt->L.mu=mu_;
  opt->L.nu=nu_;
  opt->L.lambda=lambda_;

  // enable logging
  opt->L.enableLogging = logging;
  mutex.unlock();
}








