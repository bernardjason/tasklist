@(user:User)
@import helper._


var currentDate= new Date()
function getAdjustedWeek(addToIt) {
	var d = currentDate
	var offset =  addToIt *60*60*24*1000
	d.setTime(d.getTime() + offset)		

   	var yearStart = new Date(Date.UTC(d.getUTCFullYear(),0,1));
   
    var weekNo = Math.ceil(( ( (d - yearStart) / 86400000) + 1)/7);

	return ""+d.getFullYear()+"-W"+ weekNo
}

function selectWeek(direction) {
  	$('#week').val(getAdjustedWeek(direction*7))
	loadTimeEntryData(currentDate);
}

function firstDayOfWeek (both) {

	var year = both.substring(0, 4)
	var week = parseInt( both.substring(6,9) )

   	var d = new Date(year, 0, 1),
   	offset = d.getTimezoneOffset();

   	d.setDate(d.getDate() + 4 - (d.getDay() || 7));

   	d.setTime(d.getTime() + 7 * 24 * 60 * 60 * 1000 
       	* (week + (year == d.getFullYear() ? -1 : 0 )));

   	d.setTime(d.getTime() 
       	+ (d.getTimezoneOffset() - offset) * 60 * 1000);

   	d.setDate(d.getDate() - 3);

   	return d ;
}
	
function loadTimeEntryData(forDate) {

	$("#timeentries").empty();
	$("#timeentries").append('<div class="loader"></div>')
	
	var week = null
	if (  $('#week').val() != "" ) {
  		var months = ['January','February','March','April','May','June','July','August','September','October','November','December'];
			
		var first = forDate
		if ( first  === "undefined" || first == null ) first = firstDayOfWeek( $('#week').val()  )

		currentDate = first
		var show = "Monday "+ first.getDate() +' ' +months[first.getMonth()] +' '+first.getUTCFullYear() 
			
		week = "week="+encodeURIComponent( first.getTime() )
  		$("#displayweek").text(show);
	} else {
  		$("#displayweek").text("All");
	}	
	
	var total=0.0	
	$.getJSON(			
		"api/timeentries", week,
		function(data) {
			$("#timeentries").empty();
			$.each(	data,function(key, j) {
				var head = '<tr>'

				var newone = '<td>'+ j.task+ '</td><td>' + j.effort + '</td>' +
							 '<td><button type="button" onClick="deleteEntry('+j.id+')" class="btn btn-danger">-</button></td>'
				var tail = '</tr>'
				total=total+j.effort
				
				$("#timeentries").append( head + newone +tail ); 
				});
			
			$('#total').text(total)
			
		}).fail(function(response) {
			$("#timeentries").empty();
			if ( response.status == 401 ) {
				$("#timeentries").append('<br><br><p align="right"><b>not logged in</b></p>')
			} else {
				$("#timeentries").append('<br><br><p align="right"><b>' + response.statusText + ":"+ response.status + '</b></p>')
			}
			console.log("error " + response.statusText + ":"+ response.status+" - " +response.responseText)
		  })
}

var error = null;

function deleteEntry(id) {
	var user = $('#user').val()
		
	var csrf = $('input[name=csrfToken]').val()
			
	$.ajax({
		type : "DELETE",
		headers: {
			'Csrf-Token':csrf
		 } ,
		url : "/api/timeentry?id="+id,
			success : function(data) {
				loadTimeEntryData();
			},
			error : function(response) {
				alert("error " + response.statusText + ":"+ response.status+" - " +response.responseText)
			}
		});
	}
function addTask() {
	$('#addTaskPost').modal('show');
}

function loadJavascript(){
	$('#submitTaskPostButton').on(
		'click',
		function(e) {
			var user = $('#user').val()
			
			var e = $("#task_id");
			var task_option = $("#task_id option:selected").val();
			var task = $("#task_id option:selected").text();
			var effort = $("#effort").val();
			var first = firstDayOfWeek( $('#week').val()  )
			var when = first.getTime() 
			var csrf = $('input[name=csrfToken]').val()
			var json = '{ "id":0 ,"task_id":'+ task_option+' , "when":"'+ when+'" , "task":"'+task+'","effort":'+effort+' }'
			
			$.ajax({
				type : "POST",
				headers: {
				'Csrf-Token':csrf
				 } ,
				contentType : "application/json; charset=utf-8",
				data : json,
				url : "/api/timeentry",
				success : function(data) {
					loadTimeEntryData();
				},
				error : function(response) {
					alert("error " + response.statusText + ":"+ response.status+" - " +response.responseText)
				}
			});
		});

	

    $('#postTaskButton').hover(	
		function() {
			addTask();
	 	}
	);
	$('#postTaskButton').click(	
		function() {
			$('#addTaskPost').modal('hide');
		}
	);
	$('#week').val(getAdjustedWeek(0))

	loadTimeEntryData();

	@if( user == null ) {
		$('#postTaskButton').prop('disabled', true);
	} else {
		$('#postTaskButton').prop('disabled', false);
	}
}