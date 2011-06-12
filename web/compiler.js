$(document).ready(function() {
    // TODO: handle errors that are larger than a single token
    var addTooltip = function(element, text) {
            var tooltip;
            element.mouseover(function() {
                tooltip = $(document.createElement("div"))
                    .text(text)
                    .addClass("tooltip")
                    .css({
                        top: element.offset().top + element.outerHeight(),
                        left: element.offset().left
                    })
                    .appendTo($("body"));
            });
            element.mouseout(function() {
                tooltip.remove();
            });
        },
        positionCompare = function(first, second) {
            if (first.lineNumber < second.lineNumber) {
                return -1;
            }
            if (first.lineNumber === second.lineNumber) {
                return first.characterNumber - second.characterNumber;
            }
            if (first.lineNumber > second.lineNumber) {
                return 1;
            }
        },
        findError = function(errors, currentPosition) {
            var start, end;
            for (var i = 0; i < errors.length; i += 1) {
                start = errors[i].start;
                end = errors[i].end;
                if (positionCompare(start, currentPosition) <= 0 && positionCompare(currentPosition, end) < 0) {
                    return errors[i];
                }
            }
            return null;
        },
        displayErrors = function(errors) {
            var errorsBody = $("#errors tbody").empty();
            $.each(errors, function(index, error) {
                var row = $(document.createElement("tr"));
                row.append($(document.createElement("td")).text(error.description));
                row.append($(document.createElement("td")).text("Line " + error.start.lineNumber + ", char " + error.start.characterNumber));
                errorsBody.append(row);
            });
        },
        displayHighlightedSource = function(tokens) {
            var sourceElement = $("#highlighted-source").empty();
            $.each(tokens, function(index, token) {
                var element = $(document.createElement("span"))
                    .text(token.value)
                    .addClass("token-" + token.type)
                    .appendTo(sourceElement);
            });
        },
        displaySourceWithErrors = function(tokens, errors) {
            var sourceElement = $("#source-with-errors").empty(),
                characters = $.map(tokens, function(token) {
                    return token.value;
                }).join("").split(""),
                appendErrorCharacter = function(character, error) {
                    var element = $(document.createElement("span")).text(character);
                    if (error) {
                        element.addClass("token-error");
                        addTooltip(element, error.description);
                    }
                    if (character === "\n") {
                        element.text(" \n");
                    }
                    sourceElement.append(element);
                },
                lineNumber = 1,
                characterNumber = 1;
            $.each(characters, function(index, character) {
                var error = findError(errors, {lineNumber: lineNumber, characterNumber: characterNumber});
                appendErrorCharacter(character, error);
                if (character === "\n") {
                    lineNumber += 1;
                    characterNumber = 1;
                } else {
                    characterNumber += 1;
                }
            });
            
            for (var i = 0; i < errors.length; i += 1) {
                if (positionCompare(errors[i].start, {lineNumber: lineNumber, characterNumber: characterNumber}) === 0) {
                    appendErrorCharacter(" ", errors[i])
                    return;
                }
            }
        },
        displayHighlightedSourceWithErrors = function(tokens, errors) {
            displayHighlightedSource(tokens);
            displaySourceWithErrors(tokens, errors);
        };
    $("#source input").click(function() {
        $.ajax({
            url: "/compile",
            dataType: "json",
            data: $("#source textarea").val(),
            type: "POST",
            success: function(response) {
                displayHighlightedSourceWithErrors(response.tokens, response.errors);
                displayErrors(response.errors);
            }
        });
    });
});
