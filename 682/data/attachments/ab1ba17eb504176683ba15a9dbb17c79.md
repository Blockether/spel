## DELETE http://localhost:33717/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Tue, 04 Aug 2026 14:05:03 GMT
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
curl 'http://localhost:33717/echo' \
  -X DELETE
```
