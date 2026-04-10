## DELETE http://localhost:40219/echo → 200 OK

### Request Headers
```
DELETE http://localhost:40219/echo
```

### Response Headers
```
content-length: 34
content-type: application/json
date: Fri, 10 Apr 2026 22:28:59 GMT
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
curl 'http://localhost:40219/echo' \
  -X DELETE
```
