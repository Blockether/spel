## DELETE http://localhost:45305/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Sun, 22 Nov 2026 13:21:12 GMT
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
curl 'http://localhost:45305/echo' \
  -X DELETE
```
