## DELETE http://localhost:36997/echo → 200 OK

### Request Headers
```
DELETE http://localhost:36997/echo
```

### Response Headers
```
content-length: 34
content-type: application/json
date: Fri, 06 Mar 2026 12:42:52 GMT
```

### Response Body
```json
{
  "method": "DELETE",
  "path": "/echo"
}
```

### cURL
```bash
curl 'http://localhost:36997/echo' \
  -X DELETE
```
