#define SAMPLES 256
#define SAMPLE_FREQ 100000
#define MAXDIFF 1000

int led = 13;
int sensorPinInput = 6;
int sensorPinOutput = 10;

unsigned long firstTime = 0;
int lastTimeMs = 0;
int sample = 0;

unsigned long samples[SAMPLES];
int sampleValues[SAMPLES];
int samplePos = 0;

void setup() {
  Serial.begin(115200);
  while(!Serial);  
  Serial.println("Ready!");
  pinMode(led, OUTPUT);     
  pinMode(sensorPinInput, INPUT);       
  pinMode(sensorPinOutput, OUTPUT);         

  digitalWrite(sensorPinOutput, HIGH);    // turn off IR from the keyesIR

  reset();
}

void reset() {  
  digitalWrite(led, LOW);      
  samplePos = 0;
  sample = 0;  

  for (int t=0; t<SAMPLES; t++) {
    samples[t] = 0;
    sampleValues[t] = 0;
  }  

  firstTime = micros();    
}

void analyseSamples() {
  for(int i = 0; i<SAMPLES; i++) {
      Serial.print("Signal switch at ");
      Serial.print(samples[i], DEC);
      Serial.print(" with a delay of ");
      if(i == 0) {
        Serial.print(samples[i], DEC);
      } else {
        unsigned long sampleDifference = samples[i] - samples[i-1];
        Serial.print(sampleDifference, DEC);
      }
      Serial.print(" and value: ");
      Serial.println(sampleValues[i], DEC);  
  }
}

void printNNumberOfRepeats(unsigned long n, char repeat) {
  int m = 8;
  unsigned long maxNum = 100000000;
  while(maxNum > 1) {
    if(n < maxNum) {
      m--;
    }
    maxNum = maxNum / 10;
  }
  for(int i = 1; i <= m; i++) {
    Serial.print(repeat);
  }
}

boolean firstRun = true;

// the loop routine runs over and over again forever:
void loop() {
  unsigned long samplingTime = micros()-firstTime;           
  if (samples[SAMPLES - 1] != 0 && samplePos !=0) {
    constructSamples();
    reset();
  }
  
  while(sample == digitalRead(sensorPinInput)) {}

  if (firstRun) {
    firstTime = micros();
    firstRun = false;    
  }

  sampleValues[samplePos]=sample;
  samples[samplePos++]=micros()-firstTime;
  sample = digitalRead(sensorPinInput);  
}
