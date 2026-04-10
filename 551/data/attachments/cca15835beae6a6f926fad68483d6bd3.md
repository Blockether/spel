## DELETE http://localhost:42513/echo → 200 OK

### Request Headers
```
DELETE http://localhost:42513/echo
```

### Response Headers
```
content-length: 34
content-type: application/json
date: Fri, 10 Apr 2026 21:39:02 GMT
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
curl 'http://localhost:42513/echo' \
  -X DELETE
```
