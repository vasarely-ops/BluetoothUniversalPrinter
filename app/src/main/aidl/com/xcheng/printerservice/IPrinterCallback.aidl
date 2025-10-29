package com.xcheng.printerservice;

interface IPrinterCallback {

  oneway void  onException(int code, String msg);


  oneway void  onLength(long current, long total);

  oneway void  onRealLength(double realCurrent, double realTotal);

  oneway void onComplete();
}
