## DELETE http://localhost:41399/echo → 200 OK

### Request Headers
```
DELETE http://localhost:41399/echo
```

### Response Headers
```
content-length: 34
content-type: application/json
date: Fri, 10 Apr 2026 21:38:58 GMT
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
curl 'http://localhost:41399/echo' \
  -X DELETE
```
