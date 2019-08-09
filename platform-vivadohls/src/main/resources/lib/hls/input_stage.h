/*
 * Copyright (c) EPFL VLSC, 2019
 * Author: Endri Bezati (endri.bezati@epfl.ch)
 * All rights reserved.
 *
 * License terms:
 *
 * Redistribution and use in source and binary forms,
 * with or without modification, are permitted provided
 * that the following conditions are met:
 *     * Redistributions of source code must retain the above
 *       copyright notice, this list of conditions and the
 *       following disclaimer.
 *     * Redistributions in binary form must reproduce the
 *       above copyright notice, this list of conditions and
 *       the following disclaimer in the documentation and/or
 *       other materials provided with the distribution.
 *     * Neither the name of the copyright holder nor the names
 *       of its contributors may be used to endorse or promote
 *       products derived from this software without specific
 *       prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND
 * CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
 * INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR
 * OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

#ifndef _INPUT_STAGE_H
#define _INPUT_STAGE_H

#define SENDING 0
#define WAITING_OUTPUT 1
#define READING_TO_MEMORY 2
#define BURST_READING_TO_MEMORY 3
#define DONE_SENDING 4

#include <stdint.h>
#include <hls_stream.h>

#define MAX_BUFFER_SIZE 4096

template<typename T>
class class_input_stage {
private:
	uint32_t token_counter = 0;

	uint32_t program_counter = 0;

	T buffer[MAX_BUFFER_SIZE];

public:
	uint32_t operator()(bool core_done, uint32_t requested_size, uint32_t *size,
			uint64_t *input, hls::stream<T> &STREAM);
};

template<typename T>
uint32_t class_input_stage<T>::operator()(bool core_done, uint32_t requested_size,
		uint32_t *size, uint64_t *input, hls::stream<T> &STREAM) {
#pragma HLS INLINE

	int ret = SENDING;

	switch (program_counter){
	case 0:
			goto READING;
	case 1:
			goto CHECK_OUTPUT;
	}

	CHECK_DONE: {
			if (core_done) {
				ret = DONE_SENDING;
				goto OUT;
			}else{
				goto CHECK_OUTPUT;
			}
		}

	READING: {
		for (int i = 0; i < requested_size; i++) {
#pragma HLS LOOP_TRIPCOUNT min=0 max=4096
#pragma HLS PIPELINE
			buffer[i] = input[i];
		}
		goto CHECK_DONE;
	}

	CHECK_OUTPUT:{
		if(STREAM.full()){
			ret = WAITING_OUTPUT;
			program_counter = 1;
			goto OUT;
		}else{
			if(token_counter == (requested_size -1) ){
				goto DONE;
			}else{
				goto SEND;
			}
		}
	}

	SEND:{
		T value = ( T ) buffer[token_counter];
		STREAM.write(value);
		token_counter++;
		ret = SENDING;
		goto OUT;
	}

	DONE:{
		*size = token_counter;
		program_counter = 0;
		token_counter = 0;
		ret = DONE_SENDING;
		goto OUT;
	}

	OUT: return ret;
}

#endif //_INPUT_STAGE_H
