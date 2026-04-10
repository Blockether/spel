## DELETE http://localhost:38057/echo → 200 OK

### Request Headers
```
DELETE http://localhost:38057/echo
```

### Response Headers
```
content-length: 34
content-type: application/json
date: Fri, 10 Apr 2026 03:53:03 GMT
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
curl 'http://localhost:38057/echo' \
  -X DELETE
```
