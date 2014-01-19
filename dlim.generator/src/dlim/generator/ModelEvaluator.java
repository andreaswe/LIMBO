package dlim.generator;

import java.util.ArrayList;

import org.apache.commons.math3.random.JDKRandomGenerator;
import org.eclipse.emf.ecore.EObject;

import dlim.AbsoluteValueFunction;
import dlim.ArrivalRatesFromFile;
import dlim.Combinator;
import dlim.Constant;
import dlim.CustomFunction;
import dlim.Function;
import dlim.ReferenceClockObject;
import dlim.Sequence;
import dlim.TimeDependentFunctionContainer;
import dlim.generator.util.FunctionValueCalculator;
import dlim.generator.util.TimeKeeper;

/**
 * Takes a Descartes Load Intensity Model and provides the ability to sample its arrival
 * rates at set points in time.
 * @author J�akim G. v. Kistowski
 *
 */
public class ModelEvaluator {

	//the root model element of the model's tree. This is always a Sequence.
	private Sequence rootSequence;
	
	//Evaluates simple Functions (Functions that do not contain nested Functions).
	private FunctionValueCalculator calculator;
	
	//The random number generator
	private JDKRandomGenerator rndGenerator;
	
	
	/**
	 * Creates a new ModelEvaluator for a Descartes Load Intensity Model.
	 * @param rootSequence The root model element. This is always a Sequence.
	 * @param seed The seed, with which to set up the random number generator.
	 * @param projectPath The path of the project in which the model is located.
 * 						ArrivalRatesFromFile needs this to find its arrival rate .txt files.
	 * @param noiseMode set this to IGeneratorConstants.EVALUATION for normal model evaluation,
	 * 					set it to IGeneratorConstants.CALIBRATION, if Noises are to always return 0.
	 */
	public ModelEvaluator(Sequence rootSequence, int seed, String noiseMode) {
		this.rootSequence = rootSequence;
		
		rndGenerator = new JDKRandomGenerator();
		rndGenerator.setSeed(seed);
		calculator = new FunctionValueCalculator(rndGenerator, noiseMode);
		
		ModelEvaluatorUtil.setRootSequence(rootSequence);
		TimeKeeper.calculateSequenceTime(rootSequence, 0.0);
		precompute(rootSequence);
	}
	
	/**
	 * Creates a default ModelEvaluator for the given Descartes Load Intensity Model.
	 * The default ModelEvaluator evaluates Noises with IGeneratorContstants.DEFAULTRANDOMSEED.
	 * @param rootSequence The root model element. This is always a Sequence.
	 */
	public ModelEvaluator(Sequence rootSequence) {
		this(rootSequence,IGeneratorConstants.DEFAULTRANDOMSEED,IGeneratorConstants.EVALUATION);
	}
	
	/**
	 * Evaluates the model and returns the arrival rate at the given time.
	 * @param rootTime The time for which to get the arrival rate.
	 * @return The model's arrival rate at the time.
	 */
	public double getArrivalRateAtTime(double rootTime) {
		try {
			return getFunctionNodeArrivalRate(rootSequence, rootTime, rootTime, rootTime);
		} catch(Exception e) {
			return 0.0;
		}
		
	}
	
	public double getArrivalRateDelta(double rootTime, int index, boolean[] wasMult) {
		try {
			wasMult[0] = false;
			double ci = calculateFunctionValue(rootSequence,rootTime,rootTime,rootTime);
			double ciMinus1 = 0;
			
			//pure function value
			if (index < 0) {
				return ci;
			}
			
			//sort combinators into new list
			ArrayList<Combinator> combList = new ArrayList<Combinator>();
			for (Combinator c : rootSequence.getCombine()) {
				if (c.getOperator().getLiteral().contains("MULT")) {
					combList.add(c);
				}
			}
			for (Combinator c : rootSequence.getCombine()) {
				if (!c.getOperator().getLiteral().contains("MULT")) {
					combList.add(c);
				}
			}
			
			//additive or subtractive combinator
			if (combList.get(index).getOperator().getLiteral().contains("ADD")) {
				return calculateFunctionValue(combList.get(index).getFunction(),rootTime,rootTime,rootTime);
			} else if (combList.get(index).getOperator().getLiteral().contains("SUB")) {
				return -calculateFunctionValue(combList.get(index).getFunction(),rootTime,rootTime,rootTime);
			}
			
			//multiplicative combinator
			int i = 0;
			for (Combinator c : combList) {
				ciMinus1 = ci;
				ci = combineFunction(ci,c,rootTime,rootTime,rootTime);
				if (i == index) {
					wasMult[0] = true;
					return ci - ciMinus1;
				}
				i++;
			}
			return 0.0;
		} catch (Exception e) {
			return 0.0;
		}
	}
	
	/**
	 * The time-span for which the model is defined.
	 * @return
	 */
	public double getDuration() {
		return rootSequence.getFinalDuration();
	}
	
	/**
	 * The name of the root Sequence.
	 * @return
	 */
	public String getName() {
		return rootSequence.getName();
	}
	
	/**
	 * The random number generator. This generator has already been initialized using the
	 * correct seed.
	 * @return
	 */
	protected JDKRandomGenerator getRndGenerator() {
		return rndGenerator;
	}
	
	public double getRandomNumber() {
		return rndGenerator.nextDouble();
	}
	
	public void setRandomSeed(int seed) {
		rndGenerator.setSeed(seed);
	}

	/*
	 * Gets the value of the Function f and all child Functions, which are contained
	 * in f's child Combinators.
	 */
	private double getFunctionNodeArrivalRate(Function f, double x, double rootTime, double loopTime) {
		double arr = calculateFunctionValue(f,x,rootTime,loopTime);
		for (Combinator c : f.getCombine()) {
			if (c.getOperator().getLiteral().contains("MULT")) {
				arr = combineFunction(arr,c,x,rootTime,loopTime);
			}
		}
		for (Combinator c : f.getCombine()) {
			if (!c.getOperator().getLiteral().contains("MULT")) {
				arr = combineFunction(arr,c,x,rootTime,loopTime);
			}
		}
		return arr;
	}
	
	/*
	 * Add, subtract or multiply the Function, depending on Combinator c's operator.
	 */
	private double combineFunction(double originalValue, Combinator c, double x, double rootTime, double loopTime) {
		if (c.getOperator().getLiteral().contains("ADD")) {
			return originalValue + getFunctionNodeArrivalRate(c.getFunction(), x, rootTime, loopTime);
		} else if (c.getOperator().getLiteral().contains("MULT")) {
			return originalValue * getFunctionNodeArrivalRate(c.getFunction(), x, rootTime, loopTime);
		} else {
			return originalValue - getFunctionNodeArrivalRate(c.getFunction(), x, rootTime, loopTime);
		}
	}
	
	/*
	 * Get function type and proceed accordingly.
	 * Simple Functions are passed to the FunctionValueCalculator, since they do not
	 * need to keep the rootTime and loopTime any longer.
	 */
	private double calculateFunctionValue(Function f, double x, double rootTime, double loopTime) {
		if (f instanceof Sequence) {
			return calculateSequenceValue((Sequence)f, x, rootTime, loopTime);
		} else if (f instanceof ArrivalRatesFromFile) {
			return ((ArrivalRatesFromFile) f).getArrivalRate(x);
		} else if (f instanceof Constant) {
			return ((Constant)f).getConstant();
		} else if (f instanceof CustomFunction) {
			System.out.println("Custom Functions are not supported at the moment.");
			return 0.0;
		//Univariate Functions must be handled here, because they need to be passed
		//rootTime and loopTime
		} else if (f instanceof AbsoluteValueFunction) {
			return Math.abs(getFunctionNodeArrivalRate(((AbsoluteValueFunction)f).getFunction(),x,rootTime,loopTime));
		}
		return calculator.getSimpleFunctionValue(f, x);
	}
	
	/*
	 * Calculate the arrival rate of the currently running Element within the Sequence.
	 * Return 0 if Sequence is currently inactive.
	 * Also, convert times.
	 */
	private double calculateSequenceValue(Sequence seq, double x, double rootTime, double loopTime) {
			
		double internalTime = loopTime;
		//time guard
		if (loopTime < seq.getFirstIterationStart() || loopTime >= seq.getFirstIterationEnd()) {
			if (ModelEvaluatorUtil.getParentCombinator(seq) != null) {
				return ModelEvaluatorUtil.neutralElement(ModelEvaluatorUtil.getParentCombinator(seq).getOperator());
			} else {
				return 0.0;
			}
			
		}
		//convert time to fit into first loop
		while (internalTime >= seq.getFirstIterationStart() + seq.getLoopDuration()) {
			internalTime -= seq.getLoopDuration();
		}
		double seqTime = loopTime - seq.getFirstIterationStart();
		double seqLoopTime = internalTime - seq.getFirstIterationStart();
		
		ReferenceClockObject clock = seq.getReferenceClock();
		if (clock != null) {	
			clock.setSeqTime(seqTime);
			clock.setLoopTime(seqLoopTime);
		}
		
		//calling element methods
		double seqValue = 0.0;
		for (TimeDependentFunctionContainer e : seq.getSequenceFunctionContainers()) {
			seqValue += getElementArrivalRateAtTime(e, rootTime,internalTime,seqLoopTime,seqTime);
		}
		return seqValue;
		
	}
	
	/*
	 * Calculate the arrival rate of the FunctionNode held by the Element.
	 * Return 0 if the Element is currently inactive.
	 * Convert times, depending on referenceClockType and pointOfReferenceClockObject.
	 */
	private double getElementArrivalRateAtTime(TimeDependentFunctionContainer e, double rootTime, double guardTime, double loopTime, double seqTime) {
		//time guard
		if (guardTime < e.getFirstIterationStart() || guardTime >= e.getFirstIterationEnd()) {
			return 0.0;
		}
		
		double functionValue;
		double x = rootTime;
		
		//convert to relative time for function
		if (e.getPointOfReferenceClockType().toString().contains("ELEMCLOCK")) {
			x = guardTime - e.getFirstIterationStart();
		} else if (e.getPointOfReferenceClockType().toString().contains("SEQCLOCK")) {
			if (e.getPointOfReferenceClockObject() == null) {
				x = seqTime;
			} else {
				x = e.getPointOfReferenceClockObject().getSeqTime();
			}
		} else if (e.getPointOfReferenceClockType().toString().contains("LOOPCLOCK")) {
			if (e.getPointOfReferenceClockObject() == null) {
				x = loopTime;
			} else {
				x = e.getPointOfReferenceClockObject().getLoopTime();
			}
		}
		
		if (e.getFunction() == null) {
			//undefined element: returning neutral offset value
			functionValue = 0.0;
			if (ModelEvaluatorUtil.getParentCombinator(ModelEvaluatorUtil.getParentSequence(e)) != null) {
				functionValue = ModelEvaluatorUtil.neutralElement(
						ModelEvaluatorUtil.getParentCombinator(
								ModelEvaluatorUtil.getParentSequence(e)).getOperator());
			}
		} else {
			functionValue = getFunctionNodeArrivalRate(e.getFunction(),x,rootTime,loopTime);
		}
		
		return functionValue;
	}
	
	/*
	 * Perform necessary precomputations during setup.
	 * a.k.a perform all I/O in advance and store it in memory.
	 */
	private void precompute(EObject object) {
		if (object instanceof ArrivalRatesFromFile) {
			((ArrivalRatesFromFile)object).readFile();
		}
		for (EObject child : object.eContents()) {
			precompute(child);
		}
	}
}
