package spock.adb

import com.intellij.openapi.ui.InputValidatorEx

class IPAddressInputValidator : InputValidatorEx {

		override fun checkInput(inputString: String): Boolean {
			return getErrorText(inputString) == null
		}

		override fun canClose(inputString: String?) = true

		override fun getErrorText(inputString: String): String? {
			if (!inputString.matches(Regex(pattern = "^(\\d|[1-9]\\d|1\\d\\d|2([0-4]\\d|5[0-5]))\\.(\\d|[1-9]\\d|1\\d\\d|2([0-4]\\d|5[0-5]))\\.(\\d|[1-9]\\d|1\\d\\d|2([0-4]\\d|5[0-5]))\\.(\\d|[1-9]\\d|1\\d\\d|2([0-4]\\d|5[0-5]))\$"))) {
				return "Enter Valid IP Address"
			}

			return null
		}
	}
