#include <SoftwareSerial.h>

#define SAMPLES 256
#define INSTRUCTION_SIZE 32
#define SAMPLE_FREQ 100000
#define PWMFreq 36

#define RxD 8
#define TxD 9

#define CLKFUDGE 5 // fudge factor for clock interrupt overhead
#define CLK 256 // max value for clock (timer 2)
#define PRESCALE 8 // timer2 clock prescale
#define SYSCLOCK 16000000 // main Arduino clock
#define CLKSPERUSEC (SYSCLOCK/PRESCALE/1000000) // timer clocks per microsecond

SoftwareSerial blueToothSerial(RxD, TxD);

int led = 13;

int instruction[INSTRUCTION_SIZE] = {0,0,1,1,1,1,1,0,
                                     0,0,1,1,1,1,1,1,
                                     1,0,0,0,0,0,0,0,
                                     0,0,0,0,0,0,0,1};

void setup() {
  Serial.begin(9600);
  while(!Serial);  
  pinMode(led, OUTPUT);     
  pinMode(RxD, INPUT);       
  pinMode(TxD, OUTPUT);
  enableIROut(PWMFreq);  
}

void swapInitInstruction(boolean full) {
  int replacement = 1 & full;
  
  for(int i = 0; i < 24; i++) {
    instruction[i] = replacement; 
  }
}

void mark(int time) {
  // Sends an IR mark for the specified number of microseconds.
  // The mark output is modulated at the PWM frequency.
  TCCR2A |= _BV(COM2B1); // Enable pin 3 PWM output
  delayMicroseconds(time);
}

/* Leave pin off for time (given in microseconds) */
void space(int time) {
  // Sends an IR space for the specified number of microseconds.
  // A space is no output, so the PWM output is disabled.
  TCCR2A &= ~(_BV(COM2B1)); // Disable pin 3 PWM output
  delayMicroseconds(time);
}

void enableIROut(int khz) {
  // Enables IR output. The khz value controls the modulation frequency in kilohertz.
  // The IR output will be on pin 3 (OC2B).
  // This routine is designed for 36-40KHz; if you use it for other values, it's up to you
  // to make sure it gives reasonable results. (Watch out for overflow / underflow / rounding.)
  // TIMER2 is used in phase-correct PWM mode, with OCR2A controlling the frequency and OCR2B
  // controlling the duty cycle.
  // There is no prescaling, so the output frequency is 16MHz / (2 * OCR2A)
  // To turn the output on and off, we leave the PWM running, but connect and disconnect the output pin.
  // A few hours staring at the ATmega documentation and this will all make sense.
  // See my Secrets of Arduino PWM at http://arcfn.com/2009/07/secrets-of-arduino-pwm.html for details.
  
  // Disable the Timer2 Interrupt (which is used for receiving IR)
  TIMSK2 &= ~_BV(TOIE2); //Timer2 Overflow Interrupt
  
  pinMode(3, OUTPUT);
  digitalWrite(3, LOW); // When not sending PWM, we want it low
  
  // COM2A = 00: disconnect OC2A
  // COM2B = 00: disconnect OC2B; to send signal set to 10: OC2B non-inverted
  // WGM2 = 101: phase-correct PWM with OCRA as top
  // CS2 = 000: no prescaling
  TCCR2A = _BV(WGM20);
  TCCR2B = _BV(WGM22) | _BV(CS20);
  
  // The top value for the timer. The modulation frequency will be SYSCLOCK / 2 / OCR2A.
  OCR2A = SYSCLOCK / 2 / khz / 1000;
  OCR2B = OCR2A / 3; // 33% duty cycle
}

void sendInstruction() {
  unsigned long delayTime = 0;
  unsigned long micro = 0;
  sendHead();
  for(int i = 0; i < INSTRUCTION_SIZE; i++) {
    //Serial.print(instruction[i]);
    micro = micros();
    if(instruction[i] == 0) {
      delayTime = 300;
    } else {
      delayTime = 700;
    }
    unsigned long elapsed = micros() - micro;
    space(delayTime - elapsed);
    
    mark(300);
    delayTime = 0;
  }
  space(0);
}

void sendHead() {
  unsigned long micro = micros();
  mark(2000 - (micros() - micro));
  micro = micros();
  space(2000 - (micros() - micro));
  micro = micros();
  mark(300 - (micros() - micro));
}

char *intToBin(int a) {
   int c, d, count;
   char *pointer;
 
   count = 0;
   pointer = (char*)malloc(8+1);
 
   if (pointer == NULL)
     Serial.print("Conversion fail");
 
   for (c = 7; c >= 0; c--) {
      d = a >> c;
 
      if (d & 1) {
         *(pointer+count) = 1 + '0';
      } else {
         *(pointer+count) = 0 + '0';
      }
 
      count++;
   }
   *(pointer+count) = '\0';
 
   return pointer;
}

void setPower(int power) {
  char* bitRep = intToBin(power);
  for(int i = 16; i < 24; i++) {
    instruction[i] = (int)(bitRep[i-16] - '0');
  }
  instruction[16] = 1;
  free(bitRep);
  //dumpInstruction();
}

void setDirection(int y, int x) {
  char* yBitRep = intToBin(y);
  char* xBitRep = intToBin(x);
  for(int i = 0; i < 16; i++) {
    if(i < 8) {
      instruction[i] = (int)(yBitRep[i] - '0');
    } else {
      instruction[i] = (int)(xBitRep[i-8] - '0');
    }
  }
  instruction[0] = 0;
  instruction[8] = 0;
  free(yBitRep);
  free(xBitRep);
  //dumpInstruction();
}

void setCorrection(int leftRight) {
  char* bitRep = intToBin(leftRight);
  for(int i = 24; i < 32; i++) {
    instruction[i] = (int)(bitRep[i-24] - '0');
  }
  instruction[24] = 0;
  instruction[25] = 0;
  free(bitRep);
  //dumpInstruction();
}

void dumpInstruction() {
  for(int i = 0; i < INSTRUCTION_SIZE; i++) {
    Serial.print(instruction[i]);
  }
  Serial.println();
}

String command = "";

// the loop routine runs over and over again forever:
// WARN: must only send data in sets of 4 characters.
int count = 0;
boolean firstRun = true;
void loop() {
  char data[4];
  int i = 0;
  if(firstRun) {
    swapInitInstruction(true);
    sendInstruction();
    swapInitInstruction(false);
    delay(100);
    sendInstruction();
    firstRun = false;
  } else {
    long beforeLoop = millis();
    if(Serial.available()) {
      while(Serial.available() && i < 4) {
        data[i] = (char)Serial.read();
        i++;
        delay(5);
      }
      /*
        Serial.print(data[0]);
        Serial.print(data[1]);
        Serial.print(data[2]);
        Serial.println(data[3]);
      */
      setDirection((int)data[0], (int)data[1]);
      setPower((int)data[2]);
      setCorrection((int)data[3]);
      i = 0;
      long postLoop = millis() - beforeLoop;
      if(postLoop < 100)
        delay(postLoop);
  
      sendInstruction();
    }
  }
}
