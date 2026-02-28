## DELETE http://localhost:38765/echo → 200 OK

### Request Headers
```
DELETE http://localhost:38765/echo
```

### Response Headers
```
content-length: 34
content-type: application/json
date: Sat, 28 Feb 2026 16:11:58 GMT
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
curl 'http://localhost:38765/echo' \
  -X DELETE
```
