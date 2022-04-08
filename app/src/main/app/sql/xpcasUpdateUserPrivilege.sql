alter procedure dbo.xpcasUpdateUserPrivilege (
	@KeyLong int,
	@KeyText nvarchar(50) = null,
	@Description nvarchar(50) = null
)
with encryption as
	if (exists(select * from xtcasUserPrivilege
		where KeyText = @KeyText
		  and KeyLong <> @KeyLong
		  and LastAction > 0))
	begin
		raiserror('ADO | 25 | msg.sql.DuplicateMatchcodeNotAllowed', 16, 1) with seterror
		return -1
	end

	update xtcasUserPrivilege
	set KeyText = @KeyText,
		Description = @Description
	where KeyLong = @KeyLong
return @@error