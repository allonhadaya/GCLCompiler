package gcl;

import java.io.PrintWriter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.*;

// --------------------- Codegen ---------------------------------
public class Codegen implements Mnemonic, CodegenConstants {

	private PrintWriter codefile;
	private int labelIndex = 0; // Last label issued.
	private int variableIndex = 0; // Address offset of next global allocated.
	private ConstantStorage constants = null;
	private SemanticActions.GCLErrorStream err = null; // SemanticActions.err;
	private SemanticActions.SemanticLevel currentLevel;
	private RegisterSet registers = new RegisterSet();
	private Stack<RegisterSet> savedRegisters = new Stack<RegisterSet>();
	// Honors Code
	private FileOutputStream objfile;
	private List<Instruction> instructionList = new ArrayList<Instruction>(); // List of instructions
	private Hashtable<String, Integer> definedLabels = new Hashtable<String, Integer>(); // Label definitions String name -> int instructionIndex
	Integer locationCounter = 0; // Instruction Counter

	Codegen(final SemanticActions.GCLErrorStream err) {
		this.err = err;
		init();
	}

	void setSemanticLevel(final SemanticActions.SemanticLevel currentLevel) {
		this.currentLevel = currentLevel; // gives buildOperands access to the currentLevel.
	}

	public void closeCodefile() {
		codefile.close();
	}

	public void closeObjfile() {
		if(CompilerOptions.maccOutput){
			try { objfile.close(); }
			catch (IOException e) {	e.printStackTrace(); }	
		}
	}

	/**
	 * Reserve an address for a global variable
	 *
	 * @param size the number of bytes to reserve
	 * @return the offset at which it will be stored
	 */
	public int reserveGlobalAddress(final int size) {
		int result = variableIndex;
		variableIndex += size;
		return result;
	}

	/**
	 * Get a fresh unused label
	 *
	 * @return the number of the label.
	 */
	public int getLabel() {
		return ++labelIndex;
	}

	/**
	 * How many bytes are allocated to global variables?
	 *
	 * @return the size of the block
	 */
	public int variableBlockSize() {
		return variableIndex;
	}

	/**
	 * Get an unused register or register pair -- must later be freed
	 *
	 * @param size number of registers to obtain (1 or 2)
	 * @return the register number
	 */
	public int getTemp(final int size){ // size = number of registers needed, 1 or 2.
		return registers.getTemp(size);
	}

	/**
	 * Free a register previously allocated. Only DREG and IREG operands are
	 * freed.
	 *
	 * @param mode the addressing mode used with this register
	 * @param base the register to (possibly) free
	 */
	public void freeTemp(final Mode mode, final int base) {
		registers.freeTemp(mode, base);
	}

	/**
	 * Free the register held by a location object
	 *
	 * @param l the location holding the register
	 */
	public void freeTemp(final Location l) {
		registers.freeTemp(l.mode, l.base);
	}

	public void printAllocatedRegisters() {
		registers.printAllocated();
	}

	/**
	 * Tag interface to indicate objects have a representation in the Macc
	 * runtime Used for Expressions and GCLStrings.
	 *
	 */
	interface MaccSaveable {
	}

	/**
	 * Tag interface to indicate the object behaves like a constant and has a
	 * representation in the C1 block in the Macc runtime. Used for
	 * ConstantExpressions and GCLStrings.
	 *
	 */
	interface ConstantLike extends MaccSaveable {
	}

	/**
	 * Compute a location object that will point to an expression. This is the
	 * major means of addressing SAM entities.
	 *
	 * @param semanticItem the expression whose addressability is needed
	 * @param currentlevel the scoping level in which to compute the address
	 * @return a Location object with appropriate mode, base, and displacement
	 */
	public Location buildOperands(final MaccSaveable semanticItem) {
		if (semanticItem == null || semanticItem instanceof GeneralError){
			return new Location(DREG, UNUSED, UNUSED);
		}
		if (semanticItem instanceof ConstantLike){
			return constants.insertConstant((ConstantLike) semanticItem);
		}
		Mode mode = DREG;
		int base = -1, displacement = 0;
		VariableExpression variable = (VariableExpression) semanticItem;
		// ^Safe cast, other instances of Expressions factored out above
		int itsLevel = variable.semanticLevel();
		boolean isDirect = variable.isDirect();
		if (itsLevel == CPU_LEVEL) {
			mode = isDirect ? DREG : IREG;
			base = variable.offset();
			displacement = UNUSED;
		}
		else if (itsLevel == GLOBAL_LEVEL) {
			mode = isDirect ? INDXD : IINDXD;
			base = VARIABLE_BASE;
			displacement = variable.offset();
		}
		else if (itsLevel == STACK_LEVEL) { // This may be wrong. JB.
			mode = IREG;
			base = variable.offset();
			displacement = UNUSED;
		}
		else { // procedure scope
			int diff = currentLevel.value() - itsLevel;
			if(diff == 0){
				base = FRAME_POINTER;
			}
			else if(diff == 1){
				base = STATIC_POINTER;
			}
			else{
				int reg = getTemp(1);
				gen2Address(LD, reg, INDXD, STATIC_POINTER, 2);
				for (int level = 0; level < diff-2; level++){
					gen2Address(LD, reg, INDXD, reg, 2);
				}
				freeTemp(DREG, reg);
				base = reg;
			}
			mode = isDirect ? INDXD : IINDXD;
			displacement = variable.offset();
		}
		return new Location(mode, base, displacement);
	}
	
	/**
	 * @param source block expression in memory
	 * @param inset displacement of target
	 * @return temporary expression pointing to an 'integer' at an inset from source
	 */
	Expression atInset(final Expression source, final int inset){
		
		Location loc = buildOperands(source);
		int sourceReg = getTemp(1);
		gen2Address(LD, sourceReg, loc.mode, loc.base, loc.displacement + inset);
		return new VariableExpression(IntegerType.INTEGER_TYPE, sourceReg, DIRECT);
	}
	
	/**
	 * Load a value into a register (or say which register if already loaded).
	 *
	 * @param expression the expression to be loaded
	 * @param currentlevel the scoping level from which to compute the address
	 * @return the register that was used in the load
	 */
	public int loadRegister(final Expression expression) {
		if (expression == null) {
			err.semanticError(GCLError.ILLEGAL_LOAD);
			return 0;
		}
		int size = expression.type().size() / 2; // number of registers needed
		if (size > 2) {
			err.semanticError(GCLError.ILLEGAL_LOAD_SIZE);
			return -1;
		}
		Location loc = buildOperands(expression);
		int reg;
		if (loc.mode == DREG){
			reg = loc.base;
		}
		else if (loc.mode == IREG) {
			reg = getTemp(size);
			gen2Address(LD, reg, loc);
			if (size == 2){
				gen2Address(LD, reg + 1, INDXD, loc.base, 2);
			}
			freeTemp(loc);
		} else {
			reg = getTemp(size);
			gen2Address(LD, reg, loc);
			if (size == 2){
				gen2Address(LD, reg + 1, INDXD, loc.base, loc.displacement + 2);
			}
		}
		return reg;
	}

	/**
	 * Load a value's address into a register, or say which if already loaded.
	 *
	 * @param semanticItem the expression whose address is to be loaded
	 * @param currentlevel the scoping level from which to compute the address
	 * @return the register that was used in the load
	 */
	public int loadAddress(final Expression expression) {
		if (expression == null) {
			err.semanticError(GCLError.ILLEGAL_LOAD);
			return 0;
		}
		if (!(expression instanceof VariableExpression)) {
			err.semanticError(GCLError.ILLEGAL_LOAD_ADDRESS);
			return -1;
		}
		Location loc = buildOperands(expression);
		int reg;
		VariableExpression copy = (VariableExpression) expression;
		if (loc.mode == IREG) {
			reg = loc.base;
		} else if (!copy.isDirect() && copy.semanticLevel() == 0)
			reg = loc.base;
		else {
			reg = getTemp(1);
			gen2Address(LDA, reg, loc);
		}
		return reg;
	}

	/**
	 * Load an already existing pointer into a register if it isn't already in a
	 * register. It is an error if the expression doesn't represent a pointer:
	 * i.e. "indirect".
	 *
	 * @param expression the pointer expression to be loaded
	 * @param currentLevel the the scoping level from which to compute the address
	 * @return the register that holds a copy of the pointer
	 */
	public int loadPointer(final Expression expression) {
		if (expression == null) {
			err.semanticError(GCLError.ILLEGAL_LOAD);
			return 0;
		}
		Location loc = buildOperands(expression);
		int reg = 0;
		if (loc.mode == IREG) {
			reg = loc.base; // already loaded
		} else if (loc.mode == IMEM) {
			reg = getTemp(1);
			gen2Address(LD, reg, DMEM, loc.base, loc.displacement);
			freeTemp(loc);
		} else if (loc.mode == IINDXD) {
			reg = getTemp(1);
			gen2Address(LD, reg, INDXD, loc.base, loc.displacement);
			freeTemp(loc);
		} else {
			err.semanticError(GCLError.NOT_A_POINTER);
		}
		return reg;
	}

	/**
	 * Guarantee all code has been written before we quit or at another key point
	 */
	public void flushcode() {/* Nothing in this version */
	}

	/**
	 * Replaces labels with offsets and then produces output
	 */
	public void writeInstructionList(){
		Integer offset = null;
		for(Instruction instruction : instructionList){
			if(instruction instanceof LabelReference){
				LabelReference labelInstruction = (LabelReference) instruction;
				offset = definedLabels.get(labelInstruction.label());
				if(offset == null){
					err.semanticError(GCLError.LABEL_UNDEFINED, labelInstruction.label());
				}
				else{
					labelInstruction.defineOffset(offset);
				}
			}
			codefile.println(instruction.samCode());
			if (CompilerOptions.maccOutput){
				try {objfile.write(toByteArray(instruction.maccCode(), instruction.maccSize()));}
				catch (IOException e) { e.printStackTrace(); }	
			}
		}
	}

	/**
	 * This adds the instruction to the list and optionally to the listing.
	 * @param instruction sam/macc instruction.
	 */
	private void writeFiles(final Instruction instruction) {
		instructionList.add(instruction);
		locationCounter += instruction.maccSize();
		CompilerOptions.listCode("$ " + instruction.samCode());
	}

	/**
	 * Generate a 0 address instruction such as HALT
	 *
	 * @param opcode the operation code as defined in Mnemonic
	 */
	public void gen0Address(final SamOp opcode) {
		writeFiles(new ZeroAddress(opcode));
	}
	
	/**
	 * Generate a 0 address instruction for WRNL or RDNL
	 *
	 * @param opcode the operation code as defined in Mnemonic
	 */
	public void gen0AddressIO(final SamOp opcode) {
		writeFiles(new ZeroAddressIO(opcode));
	}

	/**
	 * Generate a 1 address instruction
	 *
	 * @param opcode the operation code as defined in Mnemonic
	 * @param mode a valid addressing mode for the operand of the instruction
	 * @param base a register for the operand
	 * @param displacement an offset for the operand
	 */
	public void gen1Address(final SamOp opcode, final Mode mode, final int base, final int displacement) {
		writeFiles(new OneAddress(opcode, mode, base, displacement));
	}
	
	/**
	 * Generate a 1 address instruction for reads or writes
	 *
	 * @param opcode the operation code as defined in Mnemonic
	 * @param mode a valid addressing mode for the operand of the instruction
	 * @param base a register for the operand
	 * @param displacement an offset for the operand
	 */
	public void gen1AddressIO(final SamOp opcode, final Location loc) {
		writeFiles(new OneAddressIO(opcode, loc.mode, loc.base, loc.displacement));
	}

	/**
	 * Generate a 1 address instruction such as WRI
	 *
	 * @param opcode the operation code as defined in Mnemonic
	 * @param l a location giving the operand of the instruction
	 */
	public void gen1Address(final SamOp opcode, final Location l) {
		gen1Address(opcode, l.mode, l.base, l.displacement);
	}

	/**
	 * Generate a 2 address instruction such as IA. These are also used
	 * for INC and DEC, though reg is an amount to INC/DEC and not
	 * a register.
	 *
	 * @param opcode the operation code as defined in Mnemonic
	 * @param reg the register representing the first operand
	 * @param mode a valid addressing mode for the second operand of the instruction
	 * @param base a register for the second operand
	 * @param displacement an offset for the second operand
	 */
	public void gen2Address(final SamOp opcode, final int reg, final Mode mode, final int base, int displacement) {
		writeFiles(new TwoAddress(opcode, reg, mode, base, displacement));
	}

	/**
	 * Generate a 2 address instruction such as IA
	 *
	 * @param opcode the operation code as defined in Mnemonic
	 * @param reg the register representing the first operand
	 * @param l a location giving the second operand of the instruction
	 */
	public void gen2Address(final SamOp opcode, final int reg, final Location l) {
		gen2Address(opcode, reg, l.mode, l.base, l.displacement);
	}

	/**
	 * Generate a two address instruction with a DMEM (label) for the second
	 * operand.
	 *
	 * @param opcode the operation code as defined in Mnemnonic
	 * @param reg the register representing the first operand
	 * @param dmemLocation a string representing the second operand's label
	 */
	public void gen2Address(final SamOp opcode, final int reg, final String dmemLocation) {
		writeFiles(new TwoAddressLabel(opcode, reg, dmemLocation));
	}

	/** Set a bit of a word corresponding to a set of register numbers
	 * @param regs a list of registers to transform.<br/> DO NOT REPEAT VALUES.
	 * @return an integer with regs.length bits set
	 */
	private int regsToBits(int...regs){
		int result = 0;
		for(int reg : regs){
			result += (int)Math.pow(2, reg);
		}
		return result;
	}
	
	/**
	 * Pushes or Pops the contents of one or more registers onto the runtime stack
	 * 
	 * @param opcode PUSH or POP
	 * @param regs a list of one or more registers to push or pop
	 */
	public void genPushPopRegisters(final SamOp opcode, final int...regs){
		writeFiles(new PushPop(opcode, STACK_POINTER, regsToBits(regs)));
	}
	
	/**
	 * Pushes or Pops registers 0-10
	 * 
	 * @param opcode PUSH or POP
	 */
	public void genPushPopToStack(final SamOp opcode) {
		genPushPopRegisters(opcode, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
	}

	/** Push the contents of a register onto the runtime stack.
	 * @param reg the register to push
	 */
	public void genPushRegister(final int reg){
		genPushPopRegisters(PUSH, reg);
	}

	/** Pop the top of the runtime stack into a register.
	 * @param reg the register to receive the top of stack
	 */
	public void genPopRegister(final int reg){
		genPushPopRegisters(POP, reg);
	}

	/**
	 * Generate a jump to a label
	 *
	 * @param opcode the jump instruction
	 * @param prefix a one character prefix character
	 * @param offset the integer value of the label
	 */
	public void genJumpLabel(final SamOp opcode, final char prefix, final int offset) {
		writeFiles(new JumpToLabel(opcode, prefix + String.valueOf(offset)));
	}
	
	/**
	 * Generate a jump to a relative location
	 * 
	 * @param opcode the jump instruction
	 * @param mode a valid addressing mode for the operand of the instruction
	 * @param base a register for the operand
	 * @param displacement an offset for the operand
	 */
	public void genJumpLocation(final SamOp opcode, final Mode mode, final int base, final int displacement){
		writeFiles(new JumpToLocation(opcode, mode, base, displacement));
	}

	/**
	 * Generate a label and puts it in the hash map with the current location counter
	 *
	 * @param prefix the one character prefix
	 * @param offset the integer value of the label
	 */
	public void genLabel(final char prefix, final int offset) {
		Label instruction = new Label(LABEL_DIRECTIVE, prefix + String.valueOf(offset)); 
		definedLabels.put(instruction.name(), locationCounter);
		writeFiles(instruction);
	}

	/**
	 * Generate a SAM pseudo op INT
	 *
	 * @param value the value of the integer
	 */
	public void genIntDirective(final int value) {
		writeFiles(new IntDirective(INT_DIRECTIVE, value));
	}

	/**
	 * Generate a SAM pseudo op STRING
	 *
	 * @param value the value of the string.
	 */
	public void genStringDirective(final StringConstant value) {
		writeFiles(new StringDirective(STRING_DIRECTIVE, value));
	}

	/**
	 * Generate a SAM pseudo op SKIP
	 *
	 * @param number of bytes to skip
	 */
	public void genSkipDirective(final int value) {
		writeFiles(new Skip(value));
	}
	
	/**
	 * Comment the sam code arbitrarily -- usually for debugging
	 *
	 * @param comment the comment
	 */
	public void genCodeComment(final String comment) {
		writeFiles(new Comment(comment));
	}

	/**
	 * Stores current program counter in the static pointer and jumps to label.
	 * 
	 * @param label name of the label to jump to.
	 */
	public void genJumpSubroutine(final int label) {
		writeFiles(new TwoAddressLabel(JSR, STATIC_POINTER, "P" + label));
	}

	/** Generate the startup allocation code */
	public void genCodePreamble() {
		gen2Address(LDA, VARIABLE_BASE, "V1");
		gen2Address(LDA, CONSTANT_BASE, "C1");
		gen2Address(LD, STACK_POINTER, IMMED, 0, 32766);
		gen2Address(LD, FRAME_POINTER, DREG, STACK_POINTER, UNUSED);
		gen2Address(LD, STATIC_POINTER, DREG, STACK_POINTER, UNUSED);
	}

	/** Generate the end code, including the C1 and V1 blocks */
	public void genCodePostamble() {
		gen0Address(HALT);
		genLabel('C', 1);
		constants.genConstBlock();
		genLabel('V', 1);
		genSkipDirective(variableBlockSize());
		flushcode();
	}

	/** Save the current register set and begin with a fresh one.
	 */
	public void saveRegisterInformation(){
		savedRegisters.push(registers);
		registers = new RegisterSet();
	}

	/** Discard the current register set and return to the previous one
	 */
	private void restoreRegisterInformation(){
		registers = savedRegisters.pop();
	}

	/** Restore the dirty registers from the current register set from the runtime stack.
	 * It must have been previously pushed.
	 * @param locals the additional size of the stack to remove - usually the local data size of a proc
	 * @return the number of dirty registers in the current set
	 */
	public int popRegisters(final int locals){
		return registers.restore(locals);
	}

	/** Save the dirty registers of the current register set to the runtime stack.
	 * @param locals the additional size to reserve under the save area. Usually the local data size of a proc.
	 * @param numberUsed the number of dirty registers in the current set
	 */
	public void pushRegisters(final int locals, final int numberUsed){
		registers.save(locals, numberUsed);
		restoreRegisterInformation();
	}

	/** Manages the registers in a way that they can be saved as a set. Saving the registers as a set is
	 * useful in procedure call optimization.
	 */
	private class RegisterSet{
		private boolean[] freeRegisters = null;
		private boolean[] pair = null;
		private boolean[] wasUsed = null; //True if a register was ever used in this set. Dirty registers.

		public RegisterSet(){
			freeRegisters = new boolean[MAX_REG + 1];
			pair = new boolean[MAX_REG + 1];
			wasUsed = new boolean[MAX_REG + 1];
			for (int i = 0; i < MAX_REG; ++i) {
				freeRegisters[i] = true;
				pair[i] = false;
				wasUsed[i] = false;
			}
			freeRegisters[STATIC_POINTER] = false;
			freeRegisters[FRAME_POINTER] = false;
			freeRegisters[STACK_POINTER] = false;
			freeRegisters[CONSTANT_BASE] = false;
			freeRegisters[VARIABLE_BASE] = false;
		}


		/** Allocate one or two (adjacent) registers from this set
		 * @param size the number of registers (1 or 2)
		 * @return the register number (of the first register if two)
		 */
		public int getTemp(final int size){ // size = number of registers needed, 1 or 2.
			int result = 0;
			try {
				if (size == 1) {
					while (!freeRegisters[result]){
						result++;
					}
					freeRegisters[result] = false;
					wasUsed[result] = true;
				} else {
					while (!freeRegisters[result] || !freeRegisters[result + 1]){
						result++;
					}
					freeRegisters[result] = false;
					freeRegisters[result + 1] = false;
					wasUsed[result] = true;
					wasUsed[result + 1] = true;
					pair[result] = true;
				}
			} catch (RuntimeException e) {
				err.semanticError(GCLError.NO_REGISTER_AVAILABLE);
			}
			return result <= MAX_REG ? result : 0;
		}

		/** conditionally free a register
		 * @param mode the mode that was used with this register
		 * @param base the register to attempt to free
		 */
		public void freeTemp(final Mode mode, final int base) {
			if (mode == DREG || mode == IREG || ((mode == INDXD ||mode == IINDXD) && base <= LAST_GENERAL_REGISTER)) {
				if(freeRegisters[base]){
					return; // nothing to do
				}
				freeRegisters[base] = true;
				if (pair[base]) {
					freeRegisters[base + 1] = true;
					pair[base] = false;
				}
			}
		}

		/** Save the "dirty" registers in the set by pusing them onto the run time stack
		 * @param locals the additional space to allocate under the save area
		 * @param numberUsed the number of dirty registers in the set
		 */
		public void save(final int locals, final int numberUsed){
			int size = INT_SIZE * numberUsed + locals;
			if(size > 0){
				gen2Address(IS, STACK_POINTER, IMMED, UNUSED, size);
			}
			if(numberUsed > 0){
				int where = 0;
				for(int i = 0; i <= LAST_GENERAL_REGISTER; ++i){
					if(wasUsed[i]){
						gen2Address(STO, i, INDXD, STACK_POINTER, where);
						where += 2;
					}
				}
			}
		}

		/** Restore the registers previously saved
		 * @param locals the additional space allocated during "save"
		 */
		public int restore(final int locals){
			int numberUsed = 0;
			for(int i = 0; i <= LAST_GENERAL_REGISTER; ++i){
				if(wasUsed[i]){
					numberUsed++;
				}
			}
			if(numberUsed > 0){
				int where = 0;
				for(int i = 0; i <= LAST_GENERAL_REGISTER; ++i){
					if(wasUsed[i]){
						gen2Address(LD, i, INDXD, STACK_POINTER, where);
						where += 2;
					}
				}
			}
			int size = INT_SIZE * numberUsed + locals;
			if(size > 0){
				gen2Address(IA, STACK_POINTER, IMMED, UNUSED, size);
			}
			return numberUsed;
		}

		/** Print the allocated registers to the listing file
		 *
		 */
		public void printAllocated() {
			boolean old = CompilerOptions.listCode;
			CompilerOptions.listCode = true;
			boolean any = false;
			String which = " Allocated Registers: ";
			for (int i = 0; i < MAX_REG + 1; ++i){
				if (!freeRegisters[i]) {
					which += (i + " ");
					any = true;
				}
			}
			if (!any){
				CompilerOptions.listCode("All registers free.");
			}
			else{
				CompilerOptions.listCode(which);
			}
			CompilerOptions.listCode("");
			CompilerOptions.listCode = old;
		}
	}

	public final void init() {
		try {
			codefile = new PrintWriter(new FileOutputStream("codefile"));
		} catch (IOException e) {
			boolean old = CompilerOptions.listCode;
			CompilerOptions.listCode = true;
			CompilerOptions.listCode("File error creating codefile: " + e);
			CompilerOptions.listCode = old;
			System.exit(1);
		}
		if(CompilerOptions.maccOutput){
			try {
				objfile = new FileOutputStream("OBJ");
			} catch (IOException e) {
				boolean old = CompilerOptions.listCode;
				CompilerOptions.listCode = true;
				CompilerOptions.listCode("File error creating OBJ: " + e);
				CompilerOptions.listCode = old;
				System.exit(1);
			}	
		}
		registers = new RegisterSet();
		savedRegisters.clear();
		variableIndex = 0;
		labelIndex = 0;
		constants = new ConstantStorage(this);

	}

	// --------------------- SAM addressing modes ---------------------------
	abstract static class Mode {
		private int samCode;
		private int bytesRequired;
		public static final Mode DREG = new DREG();
		public static final Mode DMEM = new DMEM();
		public static final Mode INDXD = new INDXD();
		public static final Mode IMMED = new IMMED();
		public static final Mode IREG = new IREG();
		public static final Mode IMEM = new IMEM();
		public static final Mode IINDXD = new IINDXD();
		public static final Mode PCREL = new PCREL();

		public abstract String address(int base, int displacement);

		public int bytesRequired(){ // number of bytes required for an instruction with this mode
			assert bytesRequired==2 || bytesRequired==4;
			return this.bytesRequired;
		}

		private Mode(final int samCode, int bytesRequired) {
			this.samCode = samCode;
			this.bytesRequired = bytesRequired;
		}

		private int samCode() {
			return samCode;
		}

		private static class DREG extends Mode {
			public String address(final int base, final int displacement) {
				return ("R" + base);
			}

			private DREG() {
				super(0, 2);
			}
		}

		private static class DMEM extends Mode {
			public String address(final int base, final int displacement) {
				return (displacement >= 0 ? "+" : "") + displacement;
			}

			private DMEM() {
				super(1, 4);
			}
		}

		private static class INDXD extends Mode {
			public String address(final int base, final int displacement) {
				return (displacement >= 0 ? "+" : "") + displacement + "(R"
				+ base + ')';
			}

			private INDXD() {
				super(2, 4);
			}
		}

		private static class IMMED extends Mode {
			public String address(final int base, final int displacement) {
				return ("#" + displacement);
			}

			private IMMED() {
				super(3, 4);
			}
		}

		private static class IREG extends Mode {
			public String address(final int base, final int displacement) {
				return ("*R" + base);
			}

			private IREG() {
				super(4, 2);
			}
		}

		private static class IMEM extends Mode {
			public String address(final int base, final int displacement) {
				return ((displacement >= 0 ? "*+" : "*") + displacement); //needed?
			}

			private IMEM() {
				super(5, 4);
			}
		}

		private static class IINDXD extends Mode {
			public String address(final int base, final int displacement) {
				return (displacement >= 0 ? "*+" : "*") + displacement + "(R"
				+ base + ')';
			}

			private IINDXD() {
				super(6, 4);
			}
		}

		private static class PCREL extends Mode {
			public String address(final int base, final int displacement) {
				return ("&" + displacement);
			}

			private PCREL() {
				super(7, 4);
			}
		}
	}

	// --------------------- machine locations ---------------------------------
	static class Location { // Encapsulate a SAM address in cpu or memory. Immutable
		private Mode mode = null;
		private int base = -99;
		private int displacement = -99;

		public Location(final Mode mode, final int base, final int displacement) {
			this.mode = mode;
			this.base = base;
			this.displacement = displacement;
		}

		// Note: No accessors, but this is an inner class
		public boolean equals(Object obj) {
			try {
				Location other = (Location) obj;
				return (other.mode == mode && other.base == base && other.displacement == displacement);
			} catch (ClassCastException e) {
				return false;
			}
		}

		public int hashCode() {
			return mode.samCode() * 11 + base * 17 + displacement;
		}
	}

	// --------------------- Constant table ------ inner ------------------
	static class SamConstant { // A constant to be put into the C1 block. Immutable
		private int offset; // Offset in the C1 block
		private ConstantLike item; // value of the constant

		SamConstant(final int offset, final ConstantLike item) {
			this.offset = offset;
			this.item = item;
		}

		/**
		 * The SemanticItem at this offset in the constant block.
		 *
		 * @return the semantic item (ConstantExpression or StringItem)
		 */
		public ConstantLike item() {
			return item;
		}

		public String toString() {
			return "A const cell at offset: " + offset + ": " + item.toString();
		}
	}

	static class ConstantStorage implements Mnemonic { // The storage for C1 block constants
		private int constantOffset = 0;
		private Codegen codegen;

		// This class also serves as a warehouse for all SamConstant objects.
		private static Stack<SamConstant> storage = null;

		ConstantStorage(Codegen cg) {
			this.codegen = cg;
			storage = new Stack<SamConstant>();
		}

		public Location insertConstant(final ConstantLike semanticItem) {
			Mode mode = Codegen.INDXD;
			int base = Codegen.CONSTANT_BASE;
			int displacement = lookup(semanticItem);
			if (displacement < 0) {
				displacement = constantOffset;
				storage.push(new SamConstant(displacement, semanticItem));
				if (semanticItem instanceof Expression) { // integer or boolean constant
					constantOffset += ((Expression) semanticItem).type().size();
				}
				else { // string constant
					constantOffset += ((StringConstant) semanticItem).size();
				}

			}
			return new Location(mode, base, displacement);
		}

		public void genConstBlock() { // Generate the C1 block data
			Iterator<SamConstant> elements = elements();
			while (elements.hasNext()) {
				ConstantLike temp = (elements.next()).item();
				if (temp instanceof ConstantExpression) {
					ConstantExpression constant = (ConstantExpression) temp;
					codegen.genIntDirective(constant.value());
				}
				else {
					StringConstant constant = (StringConstant) temp;
					codegen.genStringDirective(constant);
				}

			}
		}

		public int lookup(final ConstantLike semanticItem) {
			return -1; // not implemented -- used for optimization of constants
		}

		/**
		 * Return an enumeration over all of the Sam Constants in the order in
		 * which they were created.
		 *
		 * @return an enumeration over all the sam constants
		 */
		private static Iterator<SamConstant> elements() {
			return storage.iterator();
		}

		static final void init(){ // needed to reinitialize between runs of GUICompiler
			storage = new Stack<SamConstant>();
		}
	}

	//------------------------------- Honors Code ------------------------------------
	
	// Utility functions for operations with BitSets
	
	/**
	 * Converts BitSets to byte arrays
	 * 
	 * @param bits the BitSet to convert
	 * @param maccSize the number of bytes to convert of bits.
	 * 
	 * @return Returns the first <b>[maccSize]</b> bytes of <b>[bits]</b> as a byte array.<br/>
	 * (arranged from the end of the array)
	 */
	private static byte[] toByteArray(BitSet bits, int maccSize){
	    byte[] bytes = new byte[maccSize];
	    for (int i = 0; i < (maccSize*8); i++){
	        if (bits.get(i)){
	            bytes[maccSize-(i/8)-1] |= 1<<(i%8);
	        }
	    }
	    return bytes;
	}
	
	/**
	 * Assigns the low bits of value to bits on the range [fromBit, toBit].
	 *
	 * @param bits BitSet to modify.
	 * @param fromBit index of the starting bit to be modified.
	 * @param toBit index of the ending bit to be modified.
	 * @param value integer value to be assigned to that range.
	 */
	private static void setBits(BitSet bits, int fromBit, int toBit, int value){
		if(value < 0){
			setBits(bits, fromBit, toBit, Integer.toBinaryString(value).substring(16));
			return;
		}
		for(int i = fromBit; i <= toBit; ++i){
			bits.set(i, (value % 2 == 1));
			value = value >> 1;
		}
	}
	
	/**
	 * Assigns the binary integer value of a String to bits on the range [fromBit, toBit]
	 * 
	 * @param bits BitSet to modify.
	 * @param fromBit index of the starting bit to be modified.
	 * @param toBit index of the ending bit to be modified.
	 * @param binaryString binary string value to be assigned to that range.
	 */
	private static void setBits(BitSet bits, int fromBit, int toBit, String binaryString){
		for(int characterIndex = binaryString.length()-1; fromBit <= toBit && characterIndex >= 0; characterIndex--, fromBit++){
			bits.set(fromBit, (binaryString.charAt(characterIndex) == '1') ? true : false);
		}
	}
	
	/**
	 * Assigns the binary form of value to bits along with optional zeros at the end.
	 * 
	 * @param bits BitSet to modify.
	 * @param value String value to be assigned to bits.
	 */
	private static void setBits(BitSet bits, StringConstant value){
		int bitSetIndex = 0;
		for(;bitSetIndex < value.size() - value.maccString().length(); bitSetIndex++){ // load nulls
			setBits(bits, bitSetIndex*8, (bitSetIndex+1)*8-1, 0);
		}
		for(int stringIndex = 0; stringIndex < value.maccString().length(); stringIndex++, bitSetIndex++){ // load macc string
			setBits(bits, bitSetIndex*8, (bitSetIndex+1)*8-1, value.maccString().charAt(value.maccString().length()-stringIndex-1));
		}
	}

	/**
	 * Assigns values for one word instructions.<br/><br/>
	 * Sets bits 15-11 with the first 5 bits of opcode.<br/>
	 * Sets bits 10-7 with the first 4 bits of regOne.<br/>
	 * Sets bits 6-4 with the first 3 bits of mode.<br/>
	 * Sets bits 3-0 with the first 4 bits of regTwo.
	 * 
	 * @param bits BitSet to be modified.
	 * @param opcode integer code for the operation.
	 * @param regOne number of the first register operand.
	 * @param mode integer code for the mode of the second operand.
	 * @param regTwo number of the second register operand.
	 */
	private static void setBits(BitSet bits, final int opcode, final int regOne, final int mode, final int regTwo){
		setBits(bits, 11, 15, opcode);
		setBits(bits, 7, 10, regOne);
		setBits(bits, 4, 6, mode);
		setBits(bits, 0, 3, regTwo);
	}
	
	/**
	 * Assigns values for two word instructions.<br/><br/>
	 * Sets bits 31-27 with the first 5 bits of opcode.<br/>
	 * Sets bits 26-23 with the first 4 bits of regOne.<br/>
	 * Sets bits 22-20 with the first 3 bits of mode.<br/>
	 * Sets bits 19-16 with the first 4 bits of regTwo.<br/>
	 * Sets bits 15-0 with the first 16 bits of offset.
	 * 
	 * @param bits BitSet to be modified.
	 * @param opcode integer code for the operation.
	 * @param regOne number of the first register operand.
	 * @param mode integer code for the mode of the second operand.
	 * @param regTwo number of the second register operand.
	 * @param offset the value of the second word of the instruction.
	 */
	private static void setBits(BitSet bits, final int opcode, final int regOne, final int mode, final int regTwo, final int offset){
		setBits(bits, 27, 31, opcode);
		setBits(bits, 23, 26, regOne);
		setBits(bits, 20, 22, mode);
		setBits(bits, 16, 19, regTwo);
		setBits(bits, 0, 15, offset);
	}
	
	// Classes to describe the different sam/ mac instructions.
	
	/** Instruction in sam or macc. */
	interface Instruction {

		/**
		 * @return Returns the assembly code as a String.
		 */
		public abstract String samCode();

		/**
		 * @return Returns the macc code as a BitSet.
		 */
		public abstract BitSet maccCode();

		/**
		 * @return Returns the size, in bytes, of the instruction.
		 */
		public abstract int maccSize();
	}
	
	/** Instruction which references a label.<br/>Must define an offset in the second pass. */
	interface LabelReference{
		
		/**
		 * defines the macc code once the offset of the label is known.
		 */
		public void defineOffset(final int offset);
		
		/**
		 * @return String name of the label which this instruction refers to.
		 */
		public String label();
	}
	
	/** Label instruction. Has no output in macc. */
	class Label implements Instruction{

		private final SamOp opcode;
		private final String name;

		public Label(final SamOp opcode, final String name){
			this.opcode = opcode;
			this.name = name;
		}

		public String name(){
			return name;
		}

		@Override
		public BitSet maccCode() {
			return new BitSet(0);
		}
		
		@Override
		public int maccSize() {
			return 0;
		}

		@Override
		public String samCode() {
			return opcode.samCodeString() + name;
		}
	}
	
	/** Comment instruction. Has no output in macc. */
	class Comment implements Instruction{

		private final String message;

		public Comment(final String message){
			this.message = message;
		}

		@Override
		public BitSet maccCode() {
			return new BitSet(0);
		}

		@Override
		public int maccSize() {
			return 0;
		}

		@Override
		public String samCode() {
			return ("% " + message);
		}
	}

	/** Skip instruction. Allocates memory. */
	class Skip implements Instruction{

		private final int allocatedSize;

		/**
		 * @param allocatedSize in 1 byte intervals.
		 */
		public Skip(final int allocatedSize){
			this.allocatedSize = allocatedSize;
		}

		@Override
		public BitSet maccCode() {
			return new BitSet(allocatedSize * 8);
		}

		@Override
		public int maccSize() {
			return (allocatedSize);
		}

		@Override
		public String samCode() {
			return (SKIP_DIRECTIVE.samCodeString() + allocatedSize);
		}
	}
	
	/** String directive instruction. */
	class StringDirective implements Instruction{

		private final SamOp opcode;
		private final StringConstant value;

		public StringDirective(final SamOp opcode, final StringConstant value){
			this.opcode = opcode;
			this.value = value;
		}

		@Override
		public BitSet maccCode() {
			BitSet maccCode = new BitSet(maccSize()*8);
			setBits(maccCode, value);
			return maccCode;
		}

		@Override
		public int maccSize() {
			return value.size();
		}

		@Override
		public String samCode() {
			return (opcode.samCodeString() + value.samString());
		}
	}
	
	/** Int directive instruction. */
	class IntDirective implements Instruction{

		private final SamOp opcode;
		private final int directive;
		
		public IntDirective(final SamOp opcode, final int directive){
			this.opcode = opcode;
			this.directive = directive;
		}
		
		@Override
		public String samCode() {
			return (opcode.samCodeString() + String.valueOf(directive));
		}

		@Override
		public BitSet maccCode() {
			BitSet maccCode = new BitSet(16);
			setBits(maccCode, 0, 15, directive);
			return maccCode;
		}

		@Override
		public int maccSize() {
			return 2;
		}
	}
	
	/** Push and Pop instructions. */
	class PushPop implements Instruction{

		private final SamOp opcode;
		private final int reg;
		private final int amount;

		public PushPop(final SamOp opcode, final int reg, final int amount){
			this.opcode = opcode;
			this.reg = reg;
			this.amount = amount;
		}

		@Override
		public BitSet maccCode() {
			BitSet maccCode = new BitSet(32);
			setBits(maccCode, opcode.opCodeValue(), reg, opcode.specifier(), UNUSED, amount);
			return maccCode;
		}

		@Override
		public int maccSize() {
			return 4;
		}

		@Override
		public String samCode() {
			return opcode.samCodeString() + DREG.address(reg, UNUSED) + ", " + amount;
		}
	}

	/** Instruction with zero addresses. */
	class ZeroAddress implements Instruction{

		private final SamOp opcode;

		public ZeroAddress(final SamOp opcode){
			this.opcode = opcode;
		}

		@Override
		public BitSet maccCode() {
			BitSet maccCode = new BitSet(16);
			setBits(maccCode, opcode.opCodeValue(), UNUSED, UNUSED, UNUSED);
			return maccCode;
		}

		@Override
		public int maccSize() {
			return 2;
		}

		@Override
		public String samCode() {
			return opcode.samCodeString();
		}
	}
	
	/** Read Line or Write Line instruction with no address. */
	class ZeroAddressIO implements Instruction{

		private final SamOp opcode;
		
		public ZeroAddressIO(final SamOp opcode){
			this.opcode = opcode;
		}
		
		@Override
		public BitSet maccCode() {
			BitSet maccCode = new BitSet(16);
			setBits(maccCode, opcode.opCodeValue(), opcode.specifier(), UNUSED, UNUSED);
			return maccCode;
		}

		@Override
		public int maccSize() {
			return 2;
		}

		@Override
		public String samCode() {
			return opcode.samCodeString();
		}
	}

	/** Instruction with one address. */
	class OneAddress implements Instruction{

		private final SamOp opcode;
		private final Mode mode;
		private final int base;
		private final int displacement;

		public OneAddress(final SamOp opcode, final Mode mode, final int base, final int displacement){
			this.opcode = opcode;
			this.mode = mode;
			this.base = base;
			this.displacement = displacement;
		}

		@Override
		public String samCode(){
			return (opcode.samCodeString() + mode.address(base, displacement));
		}

		@Override
		public BitSet maccCode() {
			BitSet maccCode = new BitSet(maccSize()*8);
			if(maccSize() == 4){
				setBits(maccCode, opcode.opCodeValue(), UNUSED, mode.samCode(), base, displacement);
			}
			else{
				setBits(maccCode, opcode.opCodeValue(), UNUSED, mode.samCode(), base);
			}
			return maccCode;
		}

		@Override
		public int maccSize() {
			return mode.bytesRequired();
		}
	}
	
	/** Input Output Instruction with one operand. */
	class OneAddressIO implements Instruction{

		private final SamOp opcode;
		private final Mode mode;
		private final int base;
		private final int displacement;
		
		public OneAddressIO(final SamOp opcode, final Mode mode, final int base, final int displacement){
			this.opcode = opcode;
			this.mode = mode;
			this.base = base;
			this.displacement = displacement;
		}
		
		@Override
		public BitSet maccCode() {
			BitSet maccCode = new BitSet(maccSize()*8);
			if(maccSize() == 4){
				setBits(maccCode, opcode.opCodeValue(), opcode.specifier(), mode.samCode(), base, displacement);
			}
			else{
				setBits(maccCode, opcode.opCodeValue(), opcode.specifier(), mode.samCode(), base);
			}
			return maccCode;
		}

		@Override
		public int maccSize() {
			return mode.bytesRequired();
		}

		@Override
		public String samCode(){
			return (opcode.samCodeString() + mode.address(base, displacement));
		}
	}
	
	/** Jump instruction.<br/>
	 *  Must define an offset in the second pass. */
	class JumpToLabel implements Instruction, LabelReference{

		private final SamOp opcode;
		private final String label;
		private BitSet maccCode;

		public JumpToLabel(final SamOp opcode, final String label){
			this.opcode = opcode;
			this.label = label;
		}

		@Override
		public void defineOffset(int offset){
			maccCode = new BitSet(32);
			setBits(maccCode, opcode.opCodeValue(), opcode.specifier(), DMEM.samCode(), UNUSED, offset);
		}
		
		@Override
		public String label(){
			return label;
		}

		@Override
		public BitSet maccCode() {
			return maccCode;
		}

		@Override
		public int maccSize() {
			return 4;
		}

		@Override
		public String samCode() {
			return (opcode.samCodeString() + label);
		}
	}
	
	/** Jump instruction. */
	class JumpToLocation implements Instruction{
		
		private final SamOp opcode;
		private final Mode mode;
		private final int base;
		private final int displacement;
		
		public JumpToLocation(final SamOp opcode, final Mode mode, final int base, final int displacement){
			this.opcode = opcode;
			this.mode = mode;
			this.base = base;
			this.displacement = displacement;
		}

		@Override
		public BitSet maccCode() {
			BitSet maccCode = new BitSet(mode.bytesRequired()*8);
			setBits(maccCode, opcode.opCodeValue(), opcode.specifier(), mode.samCode(), base, displacement);
			return maccCode;
		}

		@Override
		public int maccSize() {
			return mode.bytesRequired();
		}

		@Override
		public String samCode() {
			return opcode.samCodeString() + mode.address(base, displacement);
		}
	}

	/** Instruction with two addresses. */
	class TwoAddress implements Instruction{

		private final SamOp opcode;
		private final int reg;
		private final Mode mode;
		private final int base;
		private final int displacement;

		public TwoAddress(final SamOp opcode, final int reg, final Mode mode, final int base, int displacement){
			this.opcode = opcode;
			this.reg = reg;
			this.mode = mode;
			this.base = base;
			this.displacement = displacement;
		}

		@Override
		public String samCode(){
			if(opcode == Mnemonic.INC || opcode == Mnemonic.DEC){
				return (opcode.samCodeString()+ mode.address(base, displacement) + ", " + ((reg == 0) ? 16 : reg) );
			}
			return (opcode.samCodeString() + DREG.address(reg, UNUSED) + ", " + mode.address(base, displacement));
		}

		@Override
		public BitSet maccCode() {
			BitSet maccCode = new BitSet(maccSize()*8);
			if(maccSize() == 4){
				setBits(maccCode, opcode.opCodeValue(), reg, mode.samCode(), base, displacement);
			}
			else{
				setBits(maccCode, opcode.opCodeValue(), reg, mode.samCode(), base);
			}
			return maccCode;
		}

		@Override
		public int maccSize() {
			return mode.bytesRequired();
		}
	}

	/** Two Address instruction that makes use of a label.<br/>
	 *  Must define an offset in the second pass. */
	class TwoAddressLabel implements Instruction, LabelReference{
		
		private final SamOp opcode;
		private final int reg;
		private final String label;
		private BitSet maccCode;		
		
		public TwoAddressLabel(final SamOp opcode, final int reg, final String label){
			this.opcode = opcode;
			this.reg = reg;
			this.label = label;
		}
		
		@Override
		public void defineOffset(int offset){
			maccCode = new BitSet(32);
			setBits(maccCode, opcode.opCodeValue(), reg, DMEM.samCode(), UNUSED, offset);
		}

		@Override
		public String label() {
			return label;
		}
		
		@Override
		public String samCode() {
			return (opcode.samCodeString() + DREG.address(reg, UNUSED) + ", " + label);
		}
		
		@Override
		public BitSet maccCode() {
			return maccCode;
		}

		@Override
		public int maccSize() {
			return 4;
		}
	}
}