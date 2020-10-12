#pragma once
#include <Core/util.h>
#include <Optim/constrained.h>
#include "httplib.h"

struct Restserver : NonCopyable {
  OptConstrained *opt=0;
  httplib::Server server;
  std::mutex mutex;

  Restserver(OptConstrained* _opt);
  void hello_world(const httplib::Request& req, httplib::Response& res);
  void sample(const httplib::Request& req, httplib::Response& res);
  void hessian(const httplib::Request& req, httplib::Response& res);
  void numdims(const httplib::Request& req, httplib::Response& res);
  void lasteval(const httplib::Request& req, httplib::Response& res);


  void sample_lagrangian(std::vector<double>& samples, arr& origin, arr& v0, arr& v1, double d0, double d1, size_t n0, size_t n1);
  void sample_all(std::vector<std::vector<double>>& samples, arr& origin, arr& v0, arr& v1, double d0, double d1, size_t n0, size_t n1, double mu, double nu, arr &lambda);
  void hessian_at(std::vector<double>& hess, arr& origin, double mu, double nu, arr &lambda);

};

